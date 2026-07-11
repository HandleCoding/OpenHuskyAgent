package io.github.huskyagent.infra.llm.api;

import java.util.function.Consumer;

/**
 * Protocol-agnostic model HTTP client.
 * Implementations map wire formats; callers never see OpenAI/Anthropic DTOs.
 */
public interface LlmTransport {

    LlmProtocol protocol();

    LlmCapabilities capabilities();

    /**
     * Blocking non-stream completion (or stream drained internally).
     */
    LlmResult complete(LlmRequest request);

    /**
     * Stream events to {@code onEvent}. Blocking until the stream ends or fails.
     * Implementations must still produce a coherent final tool-call set via complete-style
     * events ({@link LlmStreamEvent.ToolCallDelta#complete()} / finish).
     *
     * @return aggregated result after stream completes
     */
    LlmResult stream(LlmRequest request, Consumer<LlmStreamEvent> onEvent);
}
