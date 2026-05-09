package io.github.huskyagent.infra.memory;

public record MemoryWriteResult(boolean success, String content) {
    public static MemoryWriteResult success(String content) {
        return new MemoryWriteResult(true, content);
    }

    public static MemoryWriteResult failure(String content) {
        return new MemoryWriteResult(false, content);
    }
}
