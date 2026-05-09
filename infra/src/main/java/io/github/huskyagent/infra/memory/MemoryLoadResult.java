package io.github.huskyagent.infra.memory;

public record MemoryLoadResult(String prompt) {
    public static MemoryLoadResult empty() {
        return new MemoryLoadResult("");
    }
}
