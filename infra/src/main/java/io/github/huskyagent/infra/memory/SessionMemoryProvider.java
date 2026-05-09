package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SessionMemoryProvider implements ScopedMemoryProvider {

    public static final String NAME = "session";

    private final SessionRepository sessionRepository;
    private final DataSource dataSource;
    private final MemoryManager memoryManager;
    private final MemoryScopeResolver memoryScopeResolver;

    private boolean initialized = false;

    public SessionMemoryProvider(SessionRepository sessionRepository, DataSource dataSource,
                                  MemoryManager memoryManager, MemoryScopeResolver memoryScopeResolver) {
        this.sessionRepository = sessionRepository;
        this.dataSource = dataSource;
        this.memoryManager = memoryManager;
        this.memoryScopeResolver = memoryScopeResolver;
    }

    @PostConstruct
    public void registerWithManager() {
        memoryManager.registerProvider(this);
        initialize(null);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return initialized;
    }

    @Override
    public void initialize(MemoryContext context) {
        initialized = true;
        log.info("SessionMemoryProvider initialized (Hybrid FTS5+LIKE search mode)");
    }

    @Override
    public String buildSystemPrompt() {
        return "";
    }

    @Override
    public MemoryResult prefetch(String query, MemorySearchOptions options) {
        return MemoryResult.empty(NAME);
    }

    @Override
    public MemoryResult prefetch(String query, MemorySearchOptions options, MemoryScope scope) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return MemoryResult.empty(NAME);
        }
        return MemoryResult.of(searchMessages(query, options, scope), NAME);
    }

    List<MemoryEntry> searchMessages(String query, MemorySearchOptions options, MemoryScope scope) {
        if (scope == null || scope.getCurrentSessionId() == null) {
            log.warn("session_search called without session scope, returning empty");
            return new ArrayList<>();
        }

        List<MemoryEntry> ftsResults = searchWithFts5(query, options, scope);
        if (ftsResults.isEmpty() && CJKDetector.containsCJK(query)) {
            log.debug("FTS5 returned empty + CJK query detected, falling back to LIKE for: {}", query);
            return searchWithLike(query, options, scope);
        }
        return ftsResults;
    }

    private List<MemoryEntry> searchWithFts5(String rawQuery, MemorySearchOptions options,
                                              MemoryScope scope) {
        List<MemoryEntry> entries = new ArrayList<>();
        String sanitizedQuery = Fts5QuerySanitizer.sanitize(rawQuery);
        if (sanitizedQuery.isEmpty()) return entries;

        try (Connection conn = dataSource.getConnection()) {
            QueryParts queryParts = buildFtsQuery(scope);
            PreparedStatement pstmt = conn.prepareStatement(queryParts.sql());
            int index = 1;
            pstmt.setString(index++, sanitizedQuery);
            index = bindScope(pstmt, index, scope);
            pstmt.setInt(index, options.topK());

            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                long id = rs.getLong("id");
                String content = rs.getString("content");
                String role = rs.getString("role");
                LocalDateTime createdAt = rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime() : null;
                double rank = rs.getDouble("rank");

                // FTS5 bm25 rank is negative (more negative = more relevant).
                // Negate to make positive, then add 1 so minimum score > 0.
                double score = -rank + 1;

                if (options.hasTimeRange()) {
                    long timestamp = createdAt != null
                        ? createdAt.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
                        : 0;
                    if (timestamp < options.timeRangeStart() || timestamp > options.timeRangeEnd()) {
                        continue;
                    }
                }

                if (score < options.minScore()) {
                    continue;
                }

                entries.add(MemoryEntry.of(
                    String.valueOf(id),
                    formatEntry(role, content),
                    score,
                    createdAt,
                    "session",
                    Map.of("role", role, "searchMode", "fts5")
                ));
            }

        } catch (SQLException e) {
            // FTS5 syntax error or table missing — return empty, LIKE fallback may kick in
            log.debug("FTS5 search failed: {} — will fallback to LIKE if CJK", e.getMessage());
        }

        return entries;
    }

    private List<MemoryEntry> searchWithLike(String query, MemorySearchOptions options,
                                              MemoryScope scope) {
        List<MemoryEntry> entries = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            QueryParts queryParts = buildLikeQuery(scope);
            PreparedStatement pstmt = conn.prepareStatement(queryParts.sql());
            int index = 1;
            pstmt.setString(index++, "%" + query + "%");
            index = bindScope(pstmt, index, scope);
            pstmt.setInt(index, options.topK());

            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                long id = rs.getLong("id");
                String content = rs.getString("content");
                String role = rs.getString("role");
                LocalDateTime createdAt = rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime() : null;

                double score = countOccurrences(content, query);

                if (options.hasTimeRange()) {
                    long timestamp = createdAt != null
                        ? createdAt.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
                        : 0;
                    if (timestamp < options.timeRangeStart() || timestamp > options.timeRangeEnd()) {
                        continue;
                    }
                }

                if (score < options.minScore()) {
                    continue;
                }

                entries.add(MemoryEntry.of(
                    String.valueOf(id),
                    formatEntry(role, content),
                    score,
                    createdAt,
                    "session",
                    Map.of("role", role, "searchMode", "like")
                ));
            }

        } catch (SQLException e) {
            log.error("LIKE search failed: {}", e.getMessage());
        }

        return entries;
    }

    private QueryParts buildFtsQuery(MemoryScope scope) {
        return new QueryParts("""
                    SELECT m.id, m.content, m.role, m.created_at, fts.rank
                    FROM messages_fts fts
                    JOIN messages m ON m.id = fts.rowid
                    %s
                    WHERE fts.content MATCH ? AND %s AND m.role != 'system'
                    ORDER BY fts.rank
                    LIMIT ?
                    """.formatted(scopeJoin(scope), scopePredicate(scope)));
    }

    private QueryParts buildLikeQuery(MemoryScope scope) {
        return new QueryParts("""
                    SELECT m.id, m.content, m.role, m.created_at
                    FROM messages m
                    %s
                    WHERE m.content LIKE ? AND %s AND m.role != 'system'
                    ORDER BY m.created_at DESC
                    LIMIT ?
                    """.formatted(scopeJoin(scope), scopePredicate(scope)));
    }

    private String scopeJoin(MemoryScope scope) {
        return scope.getBoundary() == MemoryScope.SearchBoundary.CURRENT_SESSION
                ? ""
                : "JOIN sessions s ON s.id = m.session_id";
    }

    private String scopePredicate(MemoryScope scope) {
        return switch (scope.getBoundary()) {
            case CURRENT_SESSION -> "m.session_id = ?";
            case SAME_PRINCIPAL -> "s.owner_principal_id = ? AND s.channel_type = ?";
            case SAME_PRINCIPAL_AND_SCENE -> "s.owner_principal_id = ? AND s.channel_type = ? AND s.scene_id = ?";
        };
    }

    private int bindScope(PreparedStatement pstmt, int index, MemoryScope scope) throws SQLException {
        switch (scope.getBoundary()) {
            case CURRENT_SESSION -> pstmt.setString(index++, scope.getCurrentSessionId());
            case SAME_PRINCIPAL -> {
                pstmt.setString(index++, scope.getPrincipalId());
                pstmt.setString(index++, scope.getChannelType());
            }
            case SAME_PRINCIPAL_AND_SCENE -> {
                pstmt.setString(index++, scope.getPrincipalId());
                pstmt.setString(index++, scope.getChannelType());
                pstmt.setString(index++, scope.getSceneId());
            }
        }
        return index;
    }

    private record QueryParts(String sql) {}

    private double countOccurrences(String content, String keyword) {
        if (content == null || keyword == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(keyword, idx)) != -1) {
            count++;
            idx += 1;
        }
        return count;
    }

    private String formatEntry(String role, String content) {
        String roleLabel = switch (role.toLowerCase()) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "system" -> "System";
            default -> role;
        };
        return "[%s]: %s".formatted(roleLabel, content);
    }

    @Override
    public void syncTurn(String user, String assistant) {
        log.debug("SessionMemoryProvider syncTurn - messages already saved via SessionRepository");
    }

    @Override
    public void syncTurn(String user, String assistant, MemoryScope scope) {
        syncTurn(user, assistant);
    }
}
