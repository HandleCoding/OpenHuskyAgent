package io.github.huskyagent.infra.llm.transport;

/**
 * Non-2xx HTTP response from a model provider.
 */
public class LlmHttpException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public LlmHttpException(int statusCode, String body) {
        super("LLM HTTP " + statusCode + ": " + truncate(body, 300));
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
