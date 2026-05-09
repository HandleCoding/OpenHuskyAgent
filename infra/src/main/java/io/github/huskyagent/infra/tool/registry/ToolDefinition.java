package io.github.huskyagent.infra.tool.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public record ToolDefinition(

    String name,

    String description,

    Toolset toolset,

    /** JSON Schema exposed to the model and UI for tool arguments. */
    JsonNode parametersSchema,

    /** Non-contextual tool handler for tools that do not need runtime scope. */
    Function<Map<String, Object>, ToolResult> handler,

    /** Context-aware tool handler for tools that depend on session/runtime state. */
    BiFunction<Map<String, Object>, ToolExecutionContext, ToolResult> contextualHandler,

    /** Returns an approval request when the current arguments require confirmation. */
    Function<Map<String, Object>, ApprovalRequest> approvalChecker,

    boolean enabled,

    String[] requiredEnvVars,

    String emoji,

    int maxResultSizeChars,

    /** Resolves the per-call timeout from arguments when a tool needs dynamic limits. */
    Function<Map<String, Object>, Duration> timeoutResolver

) {

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    public static ToolDefinition of(String name, String description, Toolset toolset,
                                    JsonNode parametersSchema,
                                    Function<Map<String, Object>, ToolResult> handler) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            handler, null, null, true, null, null, Integer.MAX_VALUE, null);
    }

    public static ToolDefinition contextual(String name, String description, Toolset toolset,
                                            JsonNode parametersSchema,
                                            BiFunction<Map<String, Object>, ToolExecutionContext, ToolResult> handler) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            null, handler, null, true, null, null, Integer.MAX_VALUE, null);
    }

    public static ToolDefinition of(String name, String description, Toolset toolset,
                                    Class<?> argsType,
                                    Function<Map<String, Object>, ToolResult> handler) {
        return new ToolDefinition(name, description, toolset, schemaFromType(argsType),
            handler, null, null, true, null, null, Integer.MAX_VALUE, null);
    }

    public static ToolDefinition withApproval(String name, String description, Toolset toolset,
                                              Class<?> argsType,
                                              Function<Map<String, Object>, ToolResult> handler,
                                              Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, schemaFromType(argsType),
            handler, null, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    public static ToolDefinition withApproval(String name, String description, Toolset toolset,
                                              JsonNode parametersSchema,
                                              Function<Map<String, Object>, ToolResult> handler,
                                              Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            handler, null, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    public static ToolDefinition withApprovalContextual(String name, String description, Toolset toolset,
                                                        JsonNode parametersSchema,
                                                        BiFunction<Map<String, Object>, ToolExecutionContext, ToolResult> handler,
                                                        Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            null, handler, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    /** Generates a JSON Schema from a typed argument class for Spring AI tool wiring. */
    private static JsonNode schemaFromType(Class<?> argsType) {
        try {
            String schemaJson = JsonSchemaGenerator.generateForType(argsType);
            return SCHEMA_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to generate JSON schema for type: " + argsType.getName(), e);
        }
    }

    public ToolDefinition withTimeout(Function<Map<String, Object>, Duration> timeoutResolver) {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, approvalChecker, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public ToolDefinition withEnabled(boolean enabled) {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, approvalChecker, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public ToolDefinition withRequiredEnvVars(String... requiredEnvVars) {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, approvalChecker, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public ToolDefinition withEmoji(String emoji) {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, approvalChecker, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public ToolDefinition withMaxResultSize(int maxResultSizeChars) {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, approvalChecker, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public ToolDefinition withoutApproval() {
        return new ToolDefinition(name, description, toolset, parametersSchema, handler,
            contextualHandler, null, enabled, requiredEnvVars, emoji, maxResultSizeChars, timeoutResolver);
    }

    public Duration resolveTimeout(Map<String, Object> args, Duration defaultTimeout) {
        if (timeoutResolver == null) {
            return defaultTimeout;
        }
        Duration timeout = timeoutResolver.apply(args);
        return timeout == null || timeout.isZero() || timeout.isNegative() ? defaultTimeout : timeout;
    }

    public boolean requiresApproval() {
        return approvalChecker != null;
    }

    public ApprovalRequest checkApproval(Map<String, Object> args) {
        if (approvalChecker == null) {
            return null;
        }
        return approvalChecker.apply(args);
    }

    public ToolResult execute(Map<String, Object> args, ToolExecutionContext context) {
        if (contextualHandler != null) {
            return contextualHandler.apply(args, context);
        }
        return handler.apply(args);
    }

    public String toToolSchema() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": \"").append(name).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(description)).append("\",\n");
        sb.append("  \"parameters\": ").append(parametersSchema.toPrettyString()).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}