package io.github.huskyagent.infra.tool.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ToolResult(

    boolean success,

    String content,

    String error,

    boolean retryable,

    String suggestedFix

) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, true, null);
    }

    public static ToolResult success(Object data) {
        try {
            return new ToolResult(true, MAPPER.writeValueAsString(data), null, true, null);
        } catch (JsonProcessingException e) {
            return failure("Failed to serialize result: " + e.getMessage());
        }
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error, true, null);
    }

    public static ToolResult failure(String error, boolean retryable, String suggestedFix) {
        return new ToolResult(false, null, error, retryable, suggestedFix);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"Failed to serialize result\"}";
        }
    }

    public ToolResult truncate(int maxSize) {
        if (content == null || content.length() <= maxSize) {
            return this;
        }
        String truncated = content.substring(0, maxSize)
            + "... [truncated, total " + content.length() + " chars]";
        return new ToolResult(success, truncated, error, retryable, suggestedFix);
    }
}
