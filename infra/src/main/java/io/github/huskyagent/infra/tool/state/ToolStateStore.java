package io.github.huskyagent.infra.tool.state;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolStateStore {

    private final ConcurrentHashMap<String, Set<String>> readFiles = new ConcurrentHashMap<>();

    public void markRead(String sessionId, String path) {
        if (sessionId == null || path == null) return;
        readFiles.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                 .add(normalize(path));
    }

    public boolean hasBeenRead(String sessionId, String path) {
        if (sessionId == null || path == null) return false;
        Set<String> paths = readFiles.get(sessionId);
        return paths != null && paths.contains(normalize(path));
    }

    public void clearSession(String sessionId) {
        readFiles.remove(sessionId);
    }

    public Set<String> getReadFiles(String sessionId) {
        return Collections.unmodifiableSet(
                readFiles.getOrDefault(sessionId, Collections.emptySet()));
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }
}
