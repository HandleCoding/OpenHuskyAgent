package io.github.huskyagent.infra.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class SessionRepository {

    private static final String SCHEMA_PATH = "/schema/session.sql";

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initDatabase();
    }

    private void initDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Run migration BEFORE schema — ensures user_id column exists
            // before CREATE INDEX references it
            migrateAddUserId(conn);

            String schema = new String(getClass().getResourceAsStream(SCHEMA_PATH).readAllBytes());
            for (String sql : schema.split(";\\n\\n")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            // Run observability migration AFTER schema — adds columns to existing databases
            migrateAddObservabilityColumns(conn);
            migrateAddIsolationColumns(conn);
            migrateAddMessageCheckpointId(conn);
            log.info("Database initialized successfully");
            rebuildFtsIndex(conn);
        } catch (Exception e) {
            log.error("Failed to initialize database", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void rebuildFtsIndex(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND name='messages_fts_insert'");
            if (!rs.next()) {
                stmt.executeUpdate("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')");
                log.info("FTS index rebuilt for legacy database");
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("FTS rebuild check failed: {}", e.getMessage());
        }
    }

    private void migrateAddUserId(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(sessions)");
            boolean hasUserId = false;
            while (rs.next()) {
                if ("user_id".equals(rs.getString("name"))) {
                    hasUserId = true;
                    break;
                }
            }
            rs.close();
            if (!hasUserId) {
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN user_id TEXT DEFAULT 'default'");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id)");
                log.info("Migrated sessions table: added user_id column");
            }
        } catch (SQLException e) {
            log.warn("Migration check for user_id failed: {}", e.getMessage());
        }
    }

    private void migrateAddObservabilityColumns(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(sessions)");
            boolean hasInputTokens = false;
            while (rs.next()) {
                if ("input_tokens".equals(rs.getString("name"))) {
                    hasInputTokens = true;
                    break;
                }
            }
            rs.close();
            if (!hasInputTokens) {
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN input_tokens INTEGER DEFAULT 0");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN output_tokens INTEGER DEFAULT 0");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN total_duration_ms INTEGER DEFAULT 0");
                log.info("Migrated sessions table: added observability columns");
            }
        } catch (SQLException e) {
            log.warn("Migration check for observability columns failed: {}", e.getMessage());
        }
    }

    private void migrateAddIsolationColumns(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(sessions)");
            boolean hasSceneId = false;
            while (rs.next()) {
                if ("scene_id".equals(rs.getString("name"))) {
                    hasSceneId = true;
                    break;
                }
            }
            rs.close();
            if (!hasSceneId) {
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN owner_principal_id TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN channel_type TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN scene_id TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN conversation_type TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN source_chat_id TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN source_thread_id TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN source_sender_id TEXT");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN session_key TEXT");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_scene_id ON sessions(scene_id)");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_channel_type ON sessions(channel_type)");
                log.info("Migrated sessions table: added isolation columns");
            }
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_owner_principal_id ON sessions(owner_principal_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_session_key ON sessions(session_key)");
        } catch (SQLException e) {
            log.warn("Migration check for isolation columns failed: {}", e.getMessage());
        }
    }

    private void migrateAddMessageCheckpointId(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(messages)");
            boolean hasCheckpointId = false;
            while (rs.next()) {
                if ("checkpoint_id".equals(rs.getString("name"))) {
                    hasCheckpointId = true;
                    break;
                }
            }
            rs.close();
            if (!hasCheckpointId) {
                stmt.executeUpdate("ALTER TABLE messages ADD COLUMN checkpoint_id TEXT");
                log.info("Migrated messages table: added checkpoint_id column");
            }
        } catch (SQLException e) {
            log.warn("Migration check for messages.checkpoint_id failed: {}", e.getMessage());
        }
    }

    // Session operations
    public SessionEntity createSession(String sessionId) {
        return createSession(sessionId, "default");
    }

    public SessionEntity createSession(String sessionId, String userId) {
        String sql = "INSERT INTO sessions (id, user_id, status) VALUES (?, ?, 'active')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();

            SessionEntity session = new SessionEntity();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setStatus("active");
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            return session;
        } catch (SQLException e) {
            log.error("Failed to create session", e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    public boolean isSessionOwnedBy(String sessionId, String userId) {
        String sql = "SELECT COUNT(*) FROM sessions WHERE id = ? AND user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, userId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            log.error("Failed to check session ownership", e);
            return false;
        }
    }

    public Optional<SessionEntity> findSession(String sessionId) {
        String sql = "SELECT * FROM sessions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapToSessionEntity(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error("Failed to find session", e);
            throw new RuntimeException("Failed to find session", e);
        }
    }

    public Optional<SessionEntity> findLatestActiveBySessionKey(String sessionKey) {
        String sql = "SELECT * FROM sessions WHERE session_key = ? AND status = 'active' ORDER BY updated_at DESC, rowid DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionKey);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapToSessionEntity(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error("Failed to find session by key", e);
            throw new RuntimeException("Failed to find session by key", e);
        }
    }

    public List<SessionEntity> findAllSessions() {
        String sql = "SELECT * FROM sessions ORDER BY updated_at DESC";
        List<SessionEntity> sessions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                sessions.add(mapToSessionEntity(rs));
            }
            return sessions;
        } catch (SQLException e) {
            log.error("Failed to find all sessions", e);
            throw new RuntimeException("Failed to find all sessions", e);
        }
    }

    public Optional<SessionEntity> findBySessionKey(String sessionKey) {
        String sql = "SELECT * FROM sessions WHERE session_key = ? AND status = 'active' ORDER BY updated_at DESC, rowid DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionKey);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapToSessionEntity(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error("Failed to find session by key", e);
            throw new RuntimeException("Failed to find session by key", e);
        }
    }

    public List<SessionEntity> findSessionsByScope(String ownerPrincipalId, String channelType, String agentId) {
        String sql = """
                SELECT * FROM sessions
                WHERE owner_principal_id = ? AND channel_type = ? AND scene_id = ? AND status = 'active'
                ORDER BY updated_at DESC
                """;
        List<SessionEntity> sessions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ownerPrincipalId);
            pstmt.setString(2, channelType);
            pstmt.setString(3, agentId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                sessions.add(mapToSessionEntity(rs));
            }
            return sessions;
        } catch (SQLException e) {
            log.error("Failed to find sessions by scope", e);
            throw new RuntimeException("Failed to find sessions by scope", e);
        }
    }

    public void updateSessionTimestamp(String sessionId) {
        String sql = "UPDATE sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update session timestamp", e);
        }
    }

    public void deactivateOtherSessionsForKey(String sessionKey, String activeSessionId) {
        String sql = "UPDATE sessions SET status = 'inactive', updated_at = CURRENT_TIMESTAMP " +
                "WHERE session_key = ? AND id <> ? AND status = 'active'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionKey);
            pstmt.setString(2, activeSessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to deactivate previous sessions for key: {}", e.getMessage());
        }
    }

    /** Update session isolation metadata (owner, channel, scene, etc.) */
    public void updateSessionMetadata(String sessionId, String metadata) {
        String sql = "UPDATE sessions SET metadata = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, metadata);
            pstmt.setString(2, sessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update session metadata: {}", e.getMessage());
        }
    }

    /** Update session isolation columns directly */
    public void updateSessionIsolation(String sessionId, String ownerPrincipalId, String channelType,
                                        String agentId, String conversationType, String sessionKey) {
        updateSessionIsolation(sessionId, ownerPrincipalId, channelType, agentId, conversationType,
                null, null, null, sessionKey);
    }

    public void updateSessionIsolation(String sessionId, String ownerPrincipalId, String channelType,
                                        String agentId, String conversationType, String sourceChatId,
                                        String sourceThreadId, String sourceSenderId, String sessionKey) {
        String sql = "UPDATE sessions SET owner_principal_id = ?, channel_type = ?, scene_id = ?, " +
                "conversation_type = ?, source_chat_id = ?, source_thread_id = ?, source_sender_id = ?, " +
                "session_key = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ownerPrincipalId);
            pstmt.setString(2, channelType);
            pstmt.setString(3, agentId);
            pstmt.setString(4, conversationType);
            pstmt.setString(5, sourceChatId);
            pstmt.setString(6, sourceThreadId);
            pstmt.setString(7, sourceSenderId);
            pstmt.setString(8, sessionKey);
            pstmt.setString(9, sessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update session isolation: {}", e.getMessage());
        }
    }

    /** Get session scene ID — used for cross-agent resume validation */
    public String getSessionAgentId(String sessionId) {
        String sql = "SELECT scene_id FROM sessions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("scene_id");
            }
            return null;
        } catch (SQLException e) {
            log.warn("Failed to get session scene: {}", e.getMessage());
            return null;
        }
    }

    // Message operations
    public Long saveMessage(String sessionId, String role, String content) {
        return saveMessage(sessionId, role, content, null);
    }

    public Long saveMessage(String sessionId, String role, String content, String checkpointId) {
        String insertSql = "INSERT INTO messages (session_id, role, content, checkpoint_id) VALUES (?, ?, ?, ?)";
        String updateSql = "UPDATE sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, sessionId);
                pstmt.setString(2, role);
                pstmt.setString(3, content);
                pstmt.setString(4, checkpointId);
                pstmt.executeUpdate();

                ResultSet rs = pstmt.getGeneratedKeys();
                long id = -1;
                if (rs.next()) {
                    id = rs.getLong(1);
                }
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, sessionId);
                    updateStmt.executeUpdate();
                }
                return id > 0 ? id : null;
            }
        } catch (SQLException e) {
            log.error("Failed to save message", e);
            throw new RuntimeException("Failed to save message", e);
        }
    }

    public int countMessages(String sessionId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count messages", e);
            return 0;
        }
    }

    public List<MessageEntity> findMessagesBySessionId(String sessionId) {
        String sql = "SELECT * FROM messages WHERE session_id = ? ORDER BY created_at ASC";
        List<MessageEntity> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                messages.add(mapToMessageEntity(rs));
            }
            return messages;
        } catch (SQLException e) {
            log.error("Failed to find messages", e);
            throw new RuntimeException("Failed to find messages", e);
        }
    }

    public List<MessageEntity> findUserMessagesBySessionId(String sessionId) {
        String sql = "SELECT * FROM messages WHERE session_id = ? AND role = 'user' ORDER BY id ASC";
        List<MessageEntity> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                messages.add(mapToMessageEntity(rs));
            }
            return messages;
        } catch (SQLException e) {
            log.error("Failed to find user messages", e);
            throw new RuntimeException("Failed to find user messages", e);
        }
    }

    public void deleteMessagesAfter(String sessionId, long afterMessageId) {
        String sql = "DELETE FROM messages WHERE session_id = ? AND id > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setLong(2, afterMessageId);
            int deleted = pstmt.executeUpdate();
            log.info("[rewind] deleted {} messages after id={} in session={}", deleted, afterMessageId, sessionId);
        } catch (SQLException e) {
            log.error("Failed to delete messages after {}", afterMessageId, e);
            throw new RuntimeException("Failed to delete messages", e);
        }
    }

    // ToolCall operations
    public Long saveToolCall(Long messageId, String toolName, String toolArgs) {
        String sql = "INSERT INTO tool_calls (message_id, tool_name, tool_args, status) VALUES (?, ?, ?, 'pending')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, messageId);
            pstmt.setString(2, toolName);
            pstmt.setString(3, toolArgs);
            pstmt.executeUpdate();

            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return null;
        } catch (SQLException e) {
            log.error("Failed to save tool call", e);
            throw new RuntimeException("Failed to save tool call", e);
        }
    }

    public void updateToolCallResult(Long toolCallId, String result, String status) {
        String sql = "UPDATE tool_calls SET result = ?, status = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, result);
            pstmt.setString(2, status);
            pstmt.setLong(3, toolCallId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update tool call result", e);
        }
    }

    // ── Observability operations ────────────────────────────────────────────────────

    public void updateSessionObservability(String sessionId, int inputTokens, int outputTokens, long durationMs) {
        String sql = "UPDATE sessions SET input_tokens = ?, output_tokens = ?, total_duration_ms = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, inputTokens);
            pstmt.setInt(2, outputTokens);
            pstmt.setLong(3, durationMs);
            pstmt.setString(4, sessionId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update session observability: {}", e.getMessage());
        }
    }

    /** Session statistics for /actuator/husky */
    public SessionStats getSessionStats() {
        String sql = "SELECT COUNT(*) as total, " +
                "COALESCE(AVG(input_tokens), 0) as avg_input, " +
                "COALESCE(AVG(output_tokens), 0) as avg_output, " +
                "COALESCE(AVG(total_duration_ms), 0) as avg_duration " +
                "FROM sessions WHERE status = 'active'";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new SessionStats(
                        rs.getInt("total"),
                        rs.getDouble("avg_input"),
                        rs.getDouble("avg_output"),
                        rs.getDouble("avg_duration"));
            }
            return new SessionStats(0, 0, 0, 0);
        } catch (SQLException e) {
            log.warn("Failed to get session stats: {}", e.getMessage());
            return new SessionStats(0, 0, 0, 0);
        }
    }

    /** Top N tools by call count for /actuator/husky */
    public List<ToolUsageStat> getToolUsageStats(int limit) {
        String sql = "SELECT tool_name, COUNT(*) as call_count FROM tool_calls GROUP BY tool_name ORDER BY call_count DESC LIMIT ?";
        List<ToolUsageStat> stats = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                stats.add(new ToolUsageStat(rs.getString("tool_name"), rs.getInt("call_count")));
            }
        } catch (SQLException e) {
            log.warn("Failed to get tool usage stats: {}", e.getMessage());
        }
        return stats;
    }

    // ── Mappers ──────────────────────────────────────────────────────────────────
    private SessionEntity mapToSessionEntity(ResultSet rs) throws SQLException {
        SessionEntity entity = new SessionEntity();
        entity.setId(rs.getString("id"));
        entity.setUserId(rs.getString("user_id"));
        entity.setOwnerPrincipalId(rs.getString("owner_principal_id"));
        entity.setChannelType(rs.getString("channel_type"));
        entity.setAgentId(rs.getString("scene_id"));
        entity.setConversationType(rs.getString("conversation_type"));
        entity.setSourceChatId(rs.getString("source_chat_id"));
        entity.setSourceThreadId(rs.getString("source_thread_id"));
        entity.setSourceSenderId(rs.getString("source_sender_id"));
        entity.setSessionKey(rs.getString("session_key"));
        entity.setCreatedAt(parseUtcToLocal(rs.getString("created_at")));
        entity.setUpdatedAt(parseUtcToLocal(rs.getString("updated_at")));
        entity.setStatus(rs.getString("status"));
        entity.setMetadata(rs.getString("metadata"));
        entity.setInputTokens(rs.getInt("input_tokens"));
        entity.setOutputTokens(rs.getInt("output_tokens"));
        entity.setTotalDurationMs(rs.getLong("total_duration_ms"));
        return entity;
    }

    private MessageEntity mapToMessageEntity(ResultSet rs) throws SQLException {
        MessageEntity entity = new MessageEntity();
        entity.setId(rs.getLong("id"));
        entity.setSessionId(rs.getString("session_id"));
        entity.setRole(rs.getString("role"));
        entity.setContent(rs.getString("content"));
        entity.setCheckpointId(rs.getString("checkpoint_id"));
        entity.setCreatedAt(parseUtcToLocal(rs.getString("created_at")));
        return entity;
    }

    private static final DateTimeFormatter SQLITE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static LocalDateTime parseUtcToLocal(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDateTime.parse(s, SQLITE_FMT)
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    // ── Stat records ──────────────────────────────────────────────────────────────

    public record SessionStats(int totalSessions, double avgInputTokens, double avgOutputTokens, double avgDurationMs) {}
    public record ToolUsageStat(String toolName, int callCount) {}
}