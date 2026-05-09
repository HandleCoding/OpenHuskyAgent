package io.github.huskyagent.infra.mcp;

import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.registry.DynamicToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpToolProvider implements DynamicToolProvider {

    private static final String PROVIDER_KEY = "mcp";

    private final McpServerConnector connector;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private volatile Runnable toolsChangedListener;

    public McpToolProvider(McpServerConnector connector, ObjectMapper objectMapper) {
        this.connector = connector;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        connector.setToolsChangeListener(this::refreshAllTools);

        loadAllTools();
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.copyOf(tools.values());
    }

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public void setToolsChangedListener(Runnable listener) {
        this.toolsChangedListener = listener;
    }

    private void loadAllTools() {
        for (String serverName : connector.getConnectedServerNames()) {
            List<McpSchema.Tool> mcpTools = connector.getTools(serverName);
            for (McpSchema.Tool mcpTool : mcpTools) {
                try {
                    String prefixedName = prefixName(serverName, mcpTool.name());
                    tools.putIfAbsent(prefixedName,
                            convertToToolDefinition(serverName, mcpTool));
                    log.debug("Registered MCP tool: {} -> {}", mcpTool.name(), prefixedName);
                } catch (Exception e) {
                    log.warn("Failed to convert MCP tool '{}' from server '{}': {}",
                            mcpTool.name(), serverName, e.getMessage());
                }
            }
        }

        log.info("McpToolProvider: {} tools from {} servers",
                tools.size(), connector.getConnectedServerNames().size());
    }

    private void refreshAllTools() {
        Map<String, ToolDefinition> newTools = new ConcurrentHashMap<>();

        for (String serverName : connector.getConnectedServerNames()) {
            List<McpSchema.Tool> mcpTools = connector.getTools(serverName);
            for (McpSchema.Tool mcpTool : mcpTools) {
                try {
                    String prefixedName = prefixName(serverName, mcpTool.name());
                    newTools.put(prefixedName, convertToToolDefinition(serverName, mcpTool));
                } catch (Exception e) {
                    log.warn("Failed to convert MCP tool '{}' from server '{}': {}",
                            mcpTool.name(), serverName, e.getMessage());
                }
            }
        }

        tools.clear();
        tools.putAll(newTools);
        if (toolsChangedListener != null) {
            toolsChangedListener.run();
        }
        log.info("McpToolProvider refreshed: {} tools from {} servers",
                tools.size(), connector.getConnectedServerNames().size());
    }

    private ToolDefinition convertToToolDefinition(String serverName, McpSchema.Tool mcpTool) {
        String prefixedName = prefixName(serverName, mcpTool.name());
        String description = mcpTool.description() != null ? mcpTool.description()
                : "MCP tool " + mcpTool.name() + " from " + serverName;

        JsonNode parametersSchema = convertSchema(mcpTool.inputSchema());

        String originalToolName = mcpTool.name();
        Function<Map<String, Object>, ToolResult> handler =
                args -> executeMcpTool(serverName, originalToolName, args);

        return ToolDefinition.of(prefixedName, description, Toolset.MCP, parametersSchema, handler);
    }

    private ToolResult executeMcpTool(String serverName, String originalToolName,
                                       Map<String, Object> args) {
        try {
            Map<String, Object> cleanArgs = new HashMap<>(args);
            cleanArgs.remove(ToolCallbackFactory.SESSION_ID_KEY);

            var request = new McpSchema.CallToolRequest(originalToolName, cleanArgs);
            var result = connector.callTool(serverName, request);

            String content = serializeContent(result);
            if (result.isError() != null && result.isError()) {
                return ToolResult.failure(content, false, "MCP server returned an error");
            }
            return ToolResult.success(content);

        } catch (McpServerConnector.CircuitOpenException e) {
            return ToolResult.failure(e.getMessage(), true, "Wait a moment and retry");
        } catch (McpServerConnector.ServerNotConnectedException e) {
            return ToolResult.failure(e.getMessage(), true, "Server may reconnect soon");
        } catch (Exception e) {
            log.error("MCP tool '{}' execution failed: {}", originalToolName, e.getMessage());
            return ToolResult.failure("MCP tool execution failed: " + e.getMessage(),
                    true, "Check if the MCP server is still running");
        }
    }

    private String serializeContent(McpSchema.CallToolResult result) {
        try {
            return objectMapper.writeValueAsString(result.content());
        } catch (Exception e) {
            return String.valueOf(result.content());
        }
    }

    private JsonNode convertSchema(Object inputSchema) {
        if (inputSchema == null) {
            return objectMapper.createObjectNode();
        }
        if (inputSchema instanceof JsonNode node) {
            return node;
        }
        return objectMapper.valueToTree(inputSchema);
    }

    public static String prefixName(String serverName, String toolName) {
        return McpToolNames.prefixName(serverName, toolName);
    }
}
