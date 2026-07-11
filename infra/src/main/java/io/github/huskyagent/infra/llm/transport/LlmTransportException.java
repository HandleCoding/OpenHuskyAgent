package io.github.huskyagent.infra.llm.transport;

/**
 * Transport-level failure (network, parse, unexpected provider shape).
 */
public class LlmTransportException extends RuntimeException {

    public LlmTransportException(String message) {
        super(message);
    }

    public LlmTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
