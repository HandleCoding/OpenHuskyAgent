package io.github.huskyagent.infra.memory;

import java.nio.file.Path;

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

    public static MemoryContext of(String sessionId, Path workingDirectory) {
        Path memoryDir = workingDirectory.resolve(".hermes").resolve("memory");
        return new MemoryContext(sessionId, workingDirectory, memoryDir, null);
    }

    public static MemoryContext of(String sessionId, Path workingDirectory, String userId) {
        Path memoryDir = workingDirectory.resolve(".hermes").resolve("memory");
        return new MemoryContext(sessionId, workingDirectory, memoryDir, userId);
    }

    public static MemoryContext stable(Path memoryDirectory) {
        return new MemoryContext(null, memoryDirectory.getParent(), memoryDirectory, null);
    }

    public static MemoryContext of(String sessionId, Path workingDirectory, Path memoryDirectory) {
        return new MemoryContext(sessionId, workingDirectory, memoryDirectory, null);
    }
}