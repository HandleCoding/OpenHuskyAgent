package io.github.huskyagent;

import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistrationIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void enableBrowserTools(DynamicPropertyRegistry registry) {
        registry.add("browser.enabled", () -> true);
        registry.add("mcp.enabled", () -> false);
    }

    @Test
    @Order(1)
    void testAllToolsRegistered() {
        System.out.println("\n📋 Test: tool registration validation");

        assertNotNull(toolRegistry, "ToolRegistry should be injected");
        assertTrue(toolRegistry.size() >= 7, "At least 7 tools should be registered");

        assertNotNull(toolRegistry.get("read_file"), "read_file should exist");
        assertNotNull(toolRegistry.get("write_file"), "write_file should exist");
        assertNotNull(toolRegistry.get("edit_file"), "edit_file should exist");
        assertNotNull(toolRegistry.get("apply_patch"), "apply_patch should exist");
        assertNull(toolRegistry.get("patch"), "patch should be renamed to apply_patch without alias");
        assertNotNull(toolRegistry.get("search_files"), "search_files should exist");
        assertNotNull(toolRegistry.get("list_files"), "list_files should exist");
        assertNotNull(toolRegistry.get("terminal"), "terminal should exist");
        assertNotNull(toolRegistry.get("process"), "process should exist");
        assertNotNull(toolRegistry.get("browser_navigate"), "browser_navigate should exist when browser.enabled=true");

        List<ToolDefinition> tools = toolRegistry.getAll();
        System.out.println("✅ Registered " + tools.size() + " tools:");
        tools.forEach(t -> System.out.println("   - " + t.name() + " [" + t.toolset().getName() + "]"));
    }

    @Test
    @Order(2)
    void testToolsetEnabled() {
        System.out.println("\n📋 Test: toolset status");

        var toolsets = toolRegistry.getRegisteredToolsets();
        assertTrue(toolsets.size() >= 3, "At least 3 toolsets should exist");
        assertEquals(7, toolRegistry.getByToolset(Toolset.BROWSER).size(), "Browser toolset should expose 7 V1 tools");

        System.out.println("✅ Toolsets:");
        toolsets.forEach(t -> {
            var toolsInSet = toolRegistry.getByToolset(t);
            System.out.println("   - " + t.getName() + ": " + toolsInSet.size() + " tools");
        });
    }

    @Test
    @Order(3)
    void testToolRegistryStats() {
        System.out.println("\n📋 Test: tool registration stats");

        var stats = toolRegistry.getStats();

        System.out.println("✅ Tool stats:");
        System.out.println("   Total tools: " + stats.get("totalTools"));
        System.out.println("   Enabled tools: " + stats.get("enabledTools"));
        System.out.println("   Toolset distribution: " + stats.get("toolsets"));
    }
}
