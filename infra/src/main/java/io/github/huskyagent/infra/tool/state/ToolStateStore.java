package io.github.huskyagent.infra.tool.state;

import io.github.huskyagent.infra.workspace.Workspace;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolStateStore {

    private final ConcurrentHashMap<String, Map<String, FileState>> readFiles = new ConcurrentHashMap<>();

    private record FileState(long mtimeMillis, boolean partialRead) {}

    public void markRead(String sessionId, String path) {
        if (sessionId == null || path == null) return;
        markRead(sessionId, Path.of(path), -1, false);
    }

    public void markRead(String sessionId, Path path, long mtimeMillis, boolean partialRead) {
        if (sessionId == null || path == null) return;
        readFiles.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                 .put(normalize(path), new FileState(mtimeMillis, partialRead));
    }

    public boolean hasBeenRead(String sessionId, String path) {
        if (sessionId == null || path == null) return false;
        return hasBeenRead(sessionId, Path.of(path));
    }

    public boolean hasBeenRead(String sessionId, Path path) {
        if (sessionId == null || path == null) return false;
        Map<String, FileState> paths = readFiles.get(sessionId);
        return paths != null && paths.containsKey(normalize(path));
    }

    public String checkBeforeWrite(String sessionId, Path path, Workspace workspace, boolean fullOverwrite) throws IOException {
        if (sessionId == null || path == null || !workspace.exists(path)) {
            return null;
        }

        String normalized = normalize(path);
        FileState state = readFiles.getOrDefault(sessionId, Collections.emptyMap()).get(normalized);
        if (state == null) {
            return "File '" + path + "' has not been read in this session. Writing may discard external changes. Consider read_file first.";
        }

        long currentMtime = workspace.getLastModifiedTime(path);
        if (state.mtimeMillis >= 0 && currentMtime != state.mtimeMillis) {
            return "File '" + path + "' was modified since it was last read in this session. Review current content before writing.";
        }

        if (fullOverwrite && state.partialRead) {
            return "File '" + path + "' was only partially read in this session. Full overwrite may discard unseen content.";
        }

        return null;
    }

    public void markWritten(String sessionId, Path path, Workspace workspace) throws IOException {
        if (sessionId == null || path == null || !workspace.exists(path)) return;
        markRead(sessionId, path, workspace.getLastModifiedTime(path), false);
    }

    public void clearSession(String sessionId) {
        readFiles.remove(sessionId);
    }

    public Set<String> getReadFiles(String sessionId) {
        Map<String, FileState> paths = readFiles.get(sessionId);
        if (paths == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(paths.keySet());
    }

    private static String normalize(Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            if (absolute.toFile().exists()) {
                return absolute.toRealPath().toAbsolutePath().normalize().toString();
            }
            return absolute.toString();
        } catch (Exception e) {
            return path.toString();
        }
    }
}
