package io.github.huskyagent.infra.tool.state;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行状态存储：记录每个 session 已读过的文件路径。
 * 供 edit_file 等写操作工具在执行前校验前置条件。
 */
@Component
public class ToolStateStore {

    private final ConcurrentHashMap<String, Set<String>> readFiles = new ConcurrentHashMap<>();

    /** read_file 成功后调用，标记该文件已被读取 */
    public void markRead(String sessionId, String path) {
        if (sessionId == null || path == null) return;
        readFiles.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                 .add(normalize(path));
    }

    /** 检查该文件是否在本 session 中被读取过 */
    public boolean hasBeenRead(String sessionId, String path) {
        if (sessionId == null || path == null) return false;
        Set<String> paths = readFiles.get(sessionId);
        return paths != null && paths.contains(normalize(path));
    }

    /** session 结束时清理，避免内存泄漏 */
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
