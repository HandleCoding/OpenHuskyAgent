package io.github.huskyagent.infra.tool;

import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(java.util.List.of());
        mapper = new ObjectMapper();
    }

    @Test
    void testRegisterTool() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("input").put("type", "string");

        ToolDefinition tool = ToolDefinition.of(
            "test_tool",
            "A test tool",
            Toolset.CORE,
            schema,
            args -> ToolResult.success("test result")
        );

        registry.register(tool);

        assertEquals(1, registry.size());
        assertTrue(registry.hasTool("test_tool"));
        assertNotNull(registry.get("test_tool"));
    }

    @Test
    void testRegisterMultipleTools() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ToolDefinition tool1 = ToolDefinition.of("tool1", "Tool 1", Toolset.CORE, schema,
            args -> ToolResult.success("result1"));
        ToolDefinition tool2 = ToolDefinition.of("tool2", "Tool 2", Toolset.SEARCH, schema,
            args -> ToolResult.success("result2"));

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(2, registry.size());
        assertTrue(registry.hasTool("tool1"));
        assertTrue(registry.hasTool("tool2"));
    }

    @Test
    void testRegisterSameNameDifferentToolsetRejected() {
        ObjectNode schema = mapper.createObjectNode();

        ToolDefinition tool1 = ToolDefinition.of("same_name", "Tool 1", Toolset.CORE, schema,
            args -> ToolResult.success("result1"));
        ToolDefinition tool2 = ToolDefinition.of("same_name", "Tool 2", Toolset.SEARCH, schema,
            args -> ToolResult.success("result2"));

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(1, registry.size());
        assertEquals(Toolset.CORE, registry.get("same_name").toolset());
    }

    @Test
    void testDeregisterTool() {
        ObjectNode schema = mapper.createObjectNode();

        ToolDefinition tool = ToolDefinition.of("test_tool", "Test", Toolset.CORE, schema,
            args -> ToolResult.success("result"));

        registry.register(tool);
        assertEquals(1, registry.size());

        registry.deregister("test_tool");
        assertEquals(0, registry.size());
        assertFalse(registry.hasTool("test_tool"));
    }

    @Test
    void testGetByToolset() {
        ObjectNode schema = mapper.createObjectNode();

        ToolDefinition coreTool = ToolDefinition.of("core_tool", "Core", Toolset.CORE, schema,
            args -> ToolResult.success("core"));
        ToolDefinition searchTool = ToolDefinition.of("search_tool", "Search", Toolset.SEARCH, schema,
            args -> ToolResult.success("search"));

        registry.register(coreTool);
        registry.register(searchTool);

        var coreTools = registry.getByToolset(Toolset.CORE);
        assertEquals(1, coreTools.size());
        assertEquals("core_tool", coreTools.get(0).name());

        var searchTools = registry.getByToolset(Toolset.SEARCH);
        assertEquals(1, searchTools.size());
    }

    @Test
    void testGetAllEnabled() {
        ObjectNode schema = mapper.createObjectNode();

        ToolDefinition enabledTool = ToolDefinition.of("enabled", "Enabled", Toolset.CORE, schema,
            args -> ToolResult.success("result"));

        ToolDefinition disabledTool = ToolDefinition.of("disabled", "Disabled", Toolset.CORE, schema,
            args -> ToolResult.success("result"))
            .withEnabled(false);

        registry.register(enabledTool);
        registry.register(disabledTool);

        var enabledTools = registry.getAllEnabled();
        assertEquals(1, enabledTools.size());
        assertEquals("enabled", enabledTools.get(0).name());
    }

    @Test
    void testReplaceProviderTools() {
        ObjectNode schema = mapper.createObjectNode();

        ToolDefinition coreTool = ToolDefinition.of("core_tool", "Core", Toolset.CORE, schema,
            args -> ToolResult.success("core"));
        ToolDefinition mcpTool1 = ToolDefinition.of("mcp_server_tool1", "MCP 1", Toolset.MCP, schema,
            args -> ToolResult.success("mcp1"));
        ToolDefinition mcpTool2 = ToolDefinition.of("mcp_server_tool2", "MCP 2", Toolset.MCP, schema,
            args -> ToolResult.success("mcp2"));

        registry.register(coreTool);
        registry.replaceProviderTools("mcp", java.util.List.of(mcpTool1));
        assertTrue(registry.hasTool("core_tool"));
        assertTrue(registry.hasTool("mcp_server_tool1"));

        registry.replaceProviderTools("mcp", java.util.List.of(mcpTool2));
        assertTrue(registry.hasTool("core_tool"));
        assertFalse(registry.hasTool("mcp_server_tool1"));
        assertTrue(registry.hasTool("mcp_server_tool2"));
    }

    @Test
    void testGetStats() {
        ObjectNode schema = mapper.createObjectNode();

        registry.register(ToolDefinition.of("tool1", "T1", Toolset.CORE, schema,
            args -> ToolResult.success("r")));
        registry.register(ToolDefinition.of("tool2", "T2", Toolset.SEARCH, schema,
            args -> ToolResult.success("r")));

        var stats = registry.getStats();

        assertEquals(2, stats.get("totalTools"));
        assertNotNull(stats.get("toolsets"));
    }
}
