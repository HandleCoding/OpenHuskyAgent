package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.config.HuskyDataPaths;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class BuiltinMemoryProvider implements ScopedMemoryProvider {

    public static final String NAME = "builtin";
    public static final String ENTRY_DELIMITER = "\n§\n";

    public static final int MEMORY_CHAR_LIMIT = 2200;
    public static final int USER_CHAR_LIMIT = 1375;

    private static final String MEMORY_FILE = "MEMORY.md";
    private static final String USER_FILE = "USER.md";

    private final MemorySecurityScanner securityScanner;
    private final MemoryManager memoryManager;
    private final HuskyDataPaths dataPaths;

    private Path memoryDirectory;
    private boolean initialized = false;

    private record Snapshot(String memory, String user) {
        private static Snapshot empty() {
            return new Snapshot("", "");
        }
    }

    private static final int MAX_SESSION_SNAPSHOTS = 1024;

    private final ConcurrentMap<String, Snapshot> sessionSnapshots = new ConcurrentHashMap<>();

    // Legacy snapshot for non-scoped callers. Runtime prompt loading uses sessionSnapshots.
    private Snapshot defaultSnapshot = Snapshot.empty();

    private List<String> memoryEntries = new ArrayList<>();
    private List<String> userEntries = new ArrayList<>();

    public BuiltinMemoryProvider(MemorySecurityScanner securityScanner, MemoryManager memoryManager, HuskyDataPaths dataPaths) {
        this.securityScanner = securityScanner;
        this.memoryManager = memoryManager;
        this.dataPaths = dataPaths;
    }

    @PostConstruct
    public void registerWithManager() {
        memoryManager.registerProvider(this);
        initialize(MemoryContext.stable(dataPaths.memoryDirectory()));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return initialized && memoryDirectory != null;
    }

    @Override
    public void initialize(MemoryContext context) {
        this.memoryDirectory = context.getMemoryDirectory();
        try {
            Files.createDirectories(memoryDirectory);
            loadFromDisk();
            defaultSnapshot = captureSnapshot(memoryEntries, userEntries);
            sessionSnapshots.clear();
            initialized = true;
            log.info("BuiltinMemoryProvider initialized at {}", memoryDirectory);
        } catch (IOException e) {
            log.error("Failed to initialize memory directory", e);
            initialized = false;
        }
    }

    private void loadFromDisk() {
        memoryEntries = readEntries(memoryDirectory.resolve(MEMORY_FILE));
        userEntries = readEntries(memoryDirectory.resolve(USER_FILE));
        log.debug("Loaded {} memory entries, {} user entries",
            memoryEntries.size(), userEntries.size());
    }

    private List<String> readEntries(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return new ArrayList<>();
            }
            return List.of(content.split(ENTRY_DELIMITER));
        } catch (IOException e) {
            log.error("Failed to read file: {}", file, e);
            return new ArrayList<>();
        }
    }

    private Snapshot captureSnapshot(List<String> memoryEntries, List<String> userEntries) {
        return new Snapshot(
            renderBlock("memory", memoryEntries, MEMORY_CHAR_LIMIT),
            renderBlock("user", userEntries, USER_CHAR_LIMIT));
    }

    private Snapshot captureSnapshotFromDisk() {
        return captureSnapshot(
            readEntries(memoryDirectory.resolve(MEMORY_FILE)),
            readEntries(memoryDirectory.resolve(USER_FILE)));
    }

    private String renderBlock(String target, List<String> entries, int limit) {
        if (entries.isEmpty()) {
            return "";
        }

        String content = String.join(ENTRY_DELIMITER, entries);
        int current = content.length();
        int pct = (int) ((current * 100.0) / limit);

        String header = target.equals("memory")
            ? "MEMORY (your personal notes) [%d%% — %d/%d chars]"
            : "USER (user profile) [%d%% — %d/%d chars]";

        String separator = "═".repeat(46);

        return String.format("""
            %s
            %s
            %s

            %s

            """, separator, String.format(header, pct, current, limit), separator, content.trim());
    }

    @Override
    public String buildSystemPrompt() {
        return buildSystemPrompt("FULL");
    }

    @Override
    public String buildSystemPrompt(String promptMode) {
        if (!isAvailable()) {
            return "";
        }

        return renderPrompt(defaultSnapshot, promptMode);
    }

    @Override
    public String buildSystemPrompt(MemoryScope scope, String promptMode) {
        if (!isAvailable()) {
            return "";
        }

        String sessionId = scope != null ? scope.getCurrentSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return renderPrompt(defaultSnapshot, promptMode);
        }
        Snapshot snapshot = snapshotForSession(sessionId);
        return renderPrompt(snapshot, promptMode);
    }

    private Snapshot snapshotForSession(String sessionId) {
        Snapshot snapshot = sessionSnapshots.computeIfAbsent(sessionId, ignored -> captureSnapshotFromDisk());
        pruneSessionSnapshotsIfNeeded();
        return snapshot;
    }

    public void clearSessionSnapshot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionSnapshots.remove(sessionId);
    }

    int sessionSnapshotCount() {
        return sessionSnapshots.size();
    }

    private void pruneSessionSnapshotsIfNeeded() {
        int overflow = sessionSnapshots.size() - MAX_SESSION_SNAPSHOTS;
        if (overflow <= 0) {
            return;
        }
        Iterator<String> iterator = sessionSnapshots.keySet().iterator();
        while (overflow > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            overflow--;
        }
    }

    private String renderPrompt(Snapshot snapshot, String promptMode) {
        String mode = promptMode != null ? promptMode : "FULL";
        StringBuilder sb = new StringBuilder();

        if (!"PROFILE_ONLY".equals(mode) && snapshot.memory() != null && !snapshot.memory().isBlank()) {
            sb.append("<memory-context>\n");
            sb.append("[System note: The following is recalled memory context, NOT new user input]\n\n");
            sb.append(snapshot.memory());
            sb.append("</memory-context>\n\n");
        }

        if (snapshot.user() != null && !snapshot.user().isBlank()) {
            sb.append("<user-context>\n");
            sb.append("[System note: User profile and preferences]\n\n");
            sb.append(snapshot.user());
            sb.append("</user-context>\n\n");
        }

        return sb.toString();
    }

    @Override
    public MemoryResult prefetch(String query, MemorySearchOptions options) {
        return prefetchSnapshot(defaultSnapshot);
    }

    @Override
    public MemoryResult prefetch(String query, MemorySearchOptions options, MemoryScope scope) {
        String sessionId = scope != null ? scope.getCurrentSessionId() : null;
        Snapshot snapshot = sessionId == null || sessionId.isBlank()
            ? defaultSnapshot
            : snapshotForSession(sessionId);
        return prefetchSnapshot(snapshot);
    }

    @Override
    public void syncTurn(String user, String assistant, MemoryScope scope) {
        // Builtin file-backed memory is updated explicitly through memory tools.
    }

    private MemoryResult prefetchSnapshot(Snapshot snapshot) {
        List<MemoryEntry> entries = new ArrayList<>();

        if (snapshot.memory() != null && !snapshot.memory().isBlank()) {
            entries.add(MemoryEntry.of(
                "memory",
                snapshot.memory(),
                1.0,
                LocalDateTime.now(),
                "memory"
            ));
        }

        if (snapshot.user() != null && !snapshot.user().isBlank()) {
            entries.add(MemoryEntry.of(
                "user",
                snapshot.user(),
                1.0,
                LocalDateTime.now(),
                "user"
            ));
        }

        return MemoryResult.cached(entries, NAME);
    }

    public String handleToolCall(String toolName, Map<String, Object> args) {
        if (!isAvailable()) {
            return "Memory provider not initialized";
        }

        try {
            switch (toolName) {
                case "memory_read":
                    return readMemory();
                case "memory_write":
                    return writeMemory((String) args.get("content"));
                case "memory_append":
                    return appendMemory((String) args.get("content"));
                case "user_read":
                    return readUser();
                case "user_write":
                    return writeUser((String) args.get("content"));
                case "user_append":
                    return appendUser((String) args.get("content"));
                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            log.error("Tool call failed: {}", toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    private String readMemory() {
        String content = String.join(ENTRY_DELIMITER, memoryEntries);
        return content.isBlank() ? "No memory entries" : content;
    }

    private String readUser() {
        String content = String.join(ENTRY_DELIMITER, userEntries);
        return content.isBlank() ? "No user entries" : content;
    }

    private String writeMemory(String content) {
        if (content == null || content.isBlank()) {
            return "Error: Content is empty";
        }

        SecurityCheckResult check = securityScanner.scan(content);
        if (check.hasWarnings()) {
            log.warn("Memory write security warnings: {}", check.warnings());
        }
        if (check.blocked()) {
            return "Blocked: " + check.blockReason();
        }

        if (content.length() > MEMORY_CHAR_LIMIT) {
            content = content.substring(0, MEMORY_CHAR_LIMIT);
            log.warn("Memory content truncated to {} chars", MEMORY_CHAR_LIMIT);
        }

        memoryEntries = List.of(content);
        writeToFile(memoryDirectory.resolve(MEMORY_FILE), content);

        return "Memory updated. Note: System prompt uses frozen snapshot, changes will appear in next session.";
    }

    private String appendMemory(String content) {
        if (content == null || content.isBlank()) {
            return "Error: Content is empty";
        }

        SecurityCheckResult check = securityScanner.scan(content);
        if (check.blocked()) {
            return "Blocked: " + check.blockReason();
        }

        List<String> newEntries = new ArrayList<>(memoryEntries);
        newEntries.add(content);

        String combined = String.join(ENTRY_DELIMITER, newEntries);
        if (combined.length() > MEMORY_CHAR_LIMIT) {
            while (combined.length() > MEMORY_CHAR_LIMIT && newEntries.size() > 1) {
                newEntries.remove(0);
                combined = String.join(ENTRY_DELIMITER, newEntries);
            }
            if (combined.length() > MEMORY_CHAR_LIMIT) {
                combined = combined.substring(0, MEMORY_CHAR_LIMIT);
            }
            log.warn("Memory content truncated due to limit");
        }

        memoryEntries = newEntries;
        writeToFile(memoryDirectory.resolve(MEMORY_FILE), combined);

        return "Memory appended. Note: System prompt uses frozen snapshot, changes will appear in next session.";
    }

    private String writeUser(String content) {
        if (content == null || content.isBlank()) {
            return "Error: Content is empty";
        }

        SecurityCheckResult check = securityScanner.scan(content);
        if (check.blocked()) {
            return "Blocked: " + check.blockReason();
        }

        if (content.length() > USER_CHAR_LIMIT) {
            content = content.substring(0, USER_CHAR_LIMIT);
            log.warn("User content truncated to {} chars", USER_CHAR_LIMIT);
        }

        userEntries = List.of(content);
        writeToFile(memoryDirectory.resolve(USER_FILE), content);

        return "User profile updated. Note: System prompt uses frozen snapshot, changes will appear in next session.";
    }

    private String appendUser(String content) {
        if (content == null || content.isBlank()) {
            return "Error: Content is empty";
        }

        SecurityCheckResult check = securityScanner.scan(content);
        if (check.blocked()) {
            return "Blocked: " + check.blockReason();
        }

        List<String> newEntries = new ArrayList<>(userEntries);
        newEntries.add(content);

        String combined = String.join(ENTRY_DELIMITER, newEntries);
        if (combined.length() > USER_CHAR_LIMIT) {
            while (combined.length() > USER_CHAR_LIMIT && newEntries.size() > 1) {
                newEntries.remove(0);
                combined = String.join(ENTRY_DELIMITER, newEntries);
            }
            if (combined.length() > USER_CHAR_LIMIT) {
                combined = combined.substring(0, USER_CHAR_LIMIT);
            }
        }

        userEntries = newEntries;
        writeToFile(memoryDirectory.resolve(USER_FILE), combined);

        return "User profile appended. Note: System prompt uses frozen snapshot, changes will appear in next session.";
    }

    private void writeToFile(Path file, String content) {
        try {
            Files.writeString(file, content);
            log.debug("Wrote to file: {}", file);
        } catch (IOException e) {
            log.error("Failed to write file: {}", file, e);
            throw new RuntimeException("Failed to write file: " + file, e);
        }
    }
}