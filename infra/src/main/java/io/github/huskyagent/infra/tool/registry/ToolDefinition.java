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

/**
 * 工具定义
 * 参考 Hermes ToolEntry 的设计
 */
public record ToolDefinition(

    /**
     * 工具名称（唯一标识）
     */
    String name,

    /**
     * 工具描述
     */
    String description,

    /**
     * 工具分组
     */
    Toolset toolset,

    /**
     * JSON Schema 定义（用于 LLM 调用）
     */
    JsonNode parametersSchema,

    /**
     * 工具处理器
     */
    Function<Map<String, Object>, ToolResult> handler,

    /**
     * 带执行上下文的工具处理器（可选）
     */
    BiFunction<Map<String, Object>, ToolExecutionContext, ToolResult> contextualHandler,

    /**
     * 审批检查器（可选）
     * 返回 ApprovalRequest 表示需要审批，返回 null 表示不需要审批
     */
    Function<Map<String, Object>, ApprovalRequest> approvalChecker,

    /**
     * 是否启用
     */
    boolean enabled,

    /**
     * 环境变量依赖（可选）
     */
    String[] requiredEnvVars,

    /**
     * emoji 标识（用于 UI 展示）
     */
    String emoji,

    /**
     * 最大结果大小（字符数）
     */
    int maxResultSizeChars,

    /**
     * 工具调用超时解析器（可按参数动态返回超时时间；返回 null 表示使用全局默认）
     */
    Function<Map<String, Object>, Duration> timeoutResolver

) {

    /** 共享 ObjectMapper，仅用于将 Spring AI 生成的 JSON Schema 字符串解析为 JsonNode */
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    /**
     * 创建简单工具定义（无需审批）
     */
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

    /**
     * 从 Java 类型自动生成 JSON Schema，创建简单工具定义。
     *
     * <p>使用 Spring AI 的 {@link JsonSchemaGenerator#generateForType} 将参数类
     * 的字段、类型和 {@code @JsonPropertyDescription} 注解自动转换为标准 JSON Schema，
     * 无需手写 {@code ObjectNode}。</p>
     *
     * <p>示例：</p>
     * <pre>{@code
     * record MyArgs(
     *     @JsonPropertyDescription("文件路径") String path,
     *     @JsonPropertyDescription("可选偏移量") Integer offset
     * ) {}
     *
     * ToolDefinition.of("my_tool", "description", Toolset.CORE, MyArgs.class, this::handle);
     * }</pre>
     */
    public static ToolDefinition of(String name, String description, Toolset toolset,
                                    Class<?> argsType,
                                    Function<Map<String, Object>, ToolResult> handler) {
        return new ToolDefinition(name, description, toolset, schemaFromType(argsType),
            handler, null, null, true, null, null, Integer.MAX_VALUE, null);
    }

    /**
     * 从 Java 类型自动生成 JSON Schema，创建带审批检查的工具定义。
     */
    public static ToolDefinition withApproval(String name, String description, Toolset toolset,
                                              Class<?> argsType,
                                              Function<Map<String, Object>, ToolResult> handler,
                                              Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, schemaFromType(argsType),
            handler, null, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    /**
     * 创建带审批检查的工具
     */
    public static ToolDefinition withApproval(String name, String description, Toolset toolset,
                                              JsonNode parametersSchema,
                                              Function<Map<String, Object>, ToolResult> handler,
                                              Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            handler, null, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    /**
     * 创建带执行上下文和审批检查的工具。
     */
    public static ToolDefinition withApprovalContextual(String name, String description, Toolset toolset,
                                                        JsonNode parametersSchema,
                                                        BiFunction<Map<String, Object>, ToolExecutionContext, ToolResult> handler,
                                                        Function<Map<String, Object>, ApprovalRequest> approvalChecker) {
        return new ToolDefinition(name, description, toolset, parametersSchema,
            null, handler, approvalChecker, true, null, null, Integer.MAX_VALUE, null);
    }

    /**
     * 使用 Spring AI JsonSchemaGenerator 从 Java 类型生成 JSON Schema JsonNode。
     */
    private static JsonNode schemaFromType(Class<?> argsType) {
        try {
            String schemaJson = JsonSchemaGenerator.generateForType(argsType);
            return SCHEMA_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to generate JSON schema for type: " + argsType.getName(), e);
        }
    }

    /**
     * 创建带动态超时的工具定义
     */
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

    /**
     * 是否需要审批
     */
    public boolean requiresApproval() {
        return approvalChecker != null;
    }

    /**
     * 检查是否需要审批，返回审批请求（如果需要）
     */
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

    /**
     * 转换为 Spring AI ToolCallback 格式
     */
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