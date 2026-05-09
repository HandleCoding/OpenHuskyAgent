package io.github.huskyagent.infra.tool.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 将 {@link ToolDefinition} 列表转换为 Spring AI {@link ToolCallback} 列表。
 *
 * <p>封装 {@code FunctionToolCallback} 构建细节和工具结果的 JSON 序列化，
 * 供 domain 层的图构建器使用，避免 domain 层直接依赖 Spring AI tool 实现类。</p>
 */
@Slf4j
@Component
public class ToolCallbackFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** sessionId 注入到 args 的保留 key，工具 handler 通过此 key 获取当前会话 ID */
    public static final String SESSION_ID_KEY = "__sessionId__";

    /**
     * 将工具定义列表转为 Spring AI ToolCallback 列表（纯执行，不含审批逻辑）
     */
    public List<ToolCallback> build(List<ToolDefinition> definitions) {
        return build(definitions, null);
    }

    /**
     * 将工具定义列表转为 Spring AI ToolCallback 列表，并将 sessionId 注入每次调用的 args。
     */
    public List<ToolCallback> build(List<ToolDefinition> definitions, String sessionId) {
        return build(definitions, sessionId, null);
    }

    public List<ToolCallback> build(List<ToolDefinition> definitions, String sessionId, ToolExecutionContext executionContext) {
        return definitions.stream()
                .map(def -> toToolCallback(def, definitions, sessionId, executionContext))
                .toList();
    }

    private ToolCallback toToolCallback(ToolDefinition def, List<ToolDefinition> definitions,
                                        String sessionId, ToolExecutionContext executionContext) {
        String inputSchema = def.parametersSchema() != null
                ? def.parametersSchema().toString()
                : "{\"type\":\"object\",\"properties\":{}}";

        return FunctionToolCallback
                .builder(def.name(), (Map<String, Object> args) -> execute(def, definitions, args, sessionId, executionContext))
                .description(def.description())
                .inputType(Map.class)
                .inputSchema(inputSchema)
                .build();
    }

    private String execute(ToolDefinition def, List<ToolDefinition> definitions, Map<String, Object> args,
                           String sessionId, ToolExecutionContext executionContext) {
        try {
            Map<String, Object> executionArgs = new java.util.HashMap<>(args);
            String effectiveSessionId = sessionId != null
                    ? sessionId
                    : executionContext != null ? executionContext.sessionId() : null;
            if (effectiveSessionId != null) {
                executionArgs.put(SESSION_ID_KEY, effectiveSessionId);
            }
            ToolExecutionContext effectiveExecutionContext = executionContext != null
                    ? executionContext
                    : ToolExecutionContext.minimal(effectiveSessionId, definitions);
            ToolResult result = def.execute(executionArgs, effectiveExecutionContext);
            if (result.success()) {
                return result.content();
            }
            return OBJECT_MAPPER.writeValueAsString(
                    Map.of("error", true, "message", result.error()));
        } catch (Exception e) {
            log.error("工具执行异常: toolName={}", def.name(), e);
            return "{\"error\":true,\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}

