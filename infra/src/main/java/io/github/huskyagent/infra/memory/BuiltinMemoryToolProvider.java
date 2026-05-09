package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置记忆工具提供者
 *
 * 将 BuiltinMemoryProvider 的写能力暴露为 Agent 可调用的工具。
 * 读取能力保留在 provider 内部，避免模型在普通对话中为了查看已注入的记忆而额外调用工具。
 */
@Component
public class BuiltinMemoryToolProvider implements ToolProvider {

    private final BuiltinMemoryProvider memoryProvider;
    private final MemoryManager memoryManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BuiltinMemoryToolProvider(BuiltinMemoryProvider memoryProvider, MemoryManager memoryManager) {
        this.memoryProvider = memoryProvider;
        this.memoryManager = memoryManager;
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
            tool("memory_write", "Overwrite the MEMORY.md file content (agent's personal notes)", contentArg()),
            tool("memory_append", "Append content to the MEMORY.md file", contentArg()),
            tool("user_write", "Overwrite the USER.md file content (user profile)", contentArg()),
            tool("user_append", "Append content to the USER.md file", contentArg())
        );
    }

    private ToolDefinition tool(String name, String description, ObjectNode schema) {
        return ToolDefinition.contextual(name, description, Toolset.MEMORY, schema, (args, context) -> {
            SessionScope scope = context != null ? context.sessionScope() : null;
            if (scope == null) {
                return ToolResult.failure("Memory tool requires runtime scope");
            }
            if ("DISABLED".equals(scope.getMemoryPolicy())) {
                return ToolResult.failure("Memory is disabled for the current scene");
            }
            if (!memoryManager.isProviderEnabled(scope, BuiltinMemoryProvider.NAME)) {
                return ToolResult.failure("Builtin memory provider is not enabled for the current scene");
            }
            if (!scope.isMemoryWriteAllowed() && isWriteTool(name)) {
                return ToolResult.failure("Memory writes are disabled for the current scene");
            }
            MemoryWriteResult result = memoryManager.writeFromTool(scope, name, args);
            return result.success() ? ToolResult.success(result.content()) : ToolResult.failure(result.content());
        });
    }

    private boolean isWriteTool(String name) {
        return name.endsWith("_write") || name.endsWith("_append");
    }

    private ObjectNode contentArg() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
            .putObject("content")
            .put("type", "string")
            .put("description", "Content to write");
        return schema;
    }
}
