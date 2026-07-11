package io.github.huskyagent.infra.llm.api;

/**
 * Normalized stream events. Transports emit these; CallModelNode (later) maps to channel tokens / AssistantMessage.
 */
public sealed interface LlmStreamEvent permits
        LlmStreamEvent.TextDelta,
        LlmStreamEvent.ReasoningDelta,
        LlmStreamEvent.ToolCallDelta,
        LlmStreamEvent.UsageEvent,
        LlmStreamEvent.Finish,
        LlmStreamEvent.ErrorEvent {

    record TextDelta(String text) implements LlmStreamEvent {
    }

    record ReasoningDelta(String text) implements LlmStreamEvent {
    }

    /**
     * Incremental or complete tool-call fragment.
     * OpenAI may send name on first delta and arguments across subsequent deltas for the same index/id.
     */
    record ToolCallDelta(
            int index,
            String id,
            String name,
            String argumentsJsonFragment,
            boolean complete
    ) implements LlmStreamEvent {
    }

    record UsageEvent(LlmUsage usage) implements LlmStreamEvent {
    }

    record Finish(String reason) implements LlmStreamEvent {
    }

    record ErrorEvent(String message, Throwable cause) implements LlmStreamEvent {
        public ErrorEvent(String message) {
            this(message, null);
        }
    }
}
