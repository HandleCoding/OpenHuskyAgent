package io.github.huskyagent.infra.runtime.watch;

public record RuntimeReloadOutcome(
        RuntimeResourceType type,
        boolean success,
        String summary,
        boolean clearPromptCache,
        boolean clearGraphCache
) {

    public static RuntimeReloadOutcome success(RuntimeResourceType type, String summary,
                                               boolean clearPromptCache,
                                               boolean clearGraphCache) {
        return new RuntimeReloadOutcome(type, true, summary, clearPromptCache, clearGraphCache);
    }

    public static RuntimeReloadOutcome failure(RuntimeResourceType type, String summary) {
        return new RuntimeReloadOutcome(type, false, summary, false, false);
    }
}
