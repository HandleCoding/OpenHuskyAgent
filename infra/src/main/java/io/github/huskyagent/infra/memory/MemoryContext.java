package io.github.huskyagent.infra.memory;

import java.nio.file.Path;

/**
 * Memory Provider 初始化上下文
 */
public class MemoryContext {

    private final String sessionId;
    private final Path workingDirectory;
    private final Path memoryDirectory;
    private final String userId;

    public MemoryContext(String sessionId, Path workingDirectory, Path memoryDirectory, String userId) {
        this.sessionId = sessionId;
        this.workingDirectory = workingDirectory;
        this.memoryDirectory = memoryDirectory;
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public Path getMemoryDirectory() {
        return memoryDirectory;
    }

    public String getUserId() {
        return userId;
    }

    /**
     * 创建默认上下文
     */
    public static MemoryContext of(String sessionId, Path workingDirectory) {
        Path memoryDir = workingDirectory.resolve(".hermes").resolve("memory");
        return new MemoryContext(sessionId, workingDirectory, memoryDir, null);
    }

    /**
     * 创建带用户 ID 的上下文
     */
    public static MemoryContext of(String sessionId, Path workingDirectory, String userId) {
        Path memoryDir = workingDirectory.resolve(".hermes").resolve("memory");
        return new MemoryContext(sessionId, workingDirectory, memoryDir, userId);
    }

    /**
     * 创建稳定记忆目录上下文
     */
    public static MemoryContext stable(Path memoryDirectory) {
        return new MemoryContext(null, memoryDirectory.getParent(), memoryDirectory, null);
    }

    /**
     * 创建带自定义记忆目录的上下文
     */
    public static MemoryContext of(String sessionId, Path workingDirectory, Path memoryDirectory) {
        return new MemoryContext(sessionId, workingDirectory, memoryDirectory, null);
    }
}