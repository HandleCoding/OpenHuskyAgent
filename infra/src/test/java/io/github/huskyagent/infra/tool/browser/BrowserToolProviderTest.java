package io.github.huskyagent.infra.tool.browser;

import com.microsoft.playwright.PlaywrightException;
import io.github.huskyagent.infra.config.BrowserConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserToolProviderTest {

    @Test
    void disabledProviderReturnsNoTools() {
        BrowserConfig config = new BrowserConfig();
        config.setEnabled(false);
        BrowserToolProvider provider = new BrowserToolProvider(config, null);

        assertTrue(provider.getTools().isEmpty());
    }

    @Test
    void enabledProviderRegistersSevenBrowserTools() {
        BrowserConfig config = new BrowserConfig();
        config.setEnabled(true);
        BrowserToolProvider provider = new BrowserToolProvider(config, null);

        List<ToolDefinition> tools = provider.getTools();

        assertEquals(7, tools.size());
        assertTrue(tools.stream().allMatch(tool -> tool.toolset() == Toolset.BROWSER));
        assertEquals(List.of(
            "browser_navigate",
            "browser_snapshot",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_press",
            "browser_back"
        ), tools.stream().map(ToolDefinition::name).toList());
    }

    @Test
    void schemasRequireExpectedFields() {
        BrowserConfig config = new BrowserConfig();
        config.setEnabled(true);
        BrowserToolProvider provider = new BrowserToolProvider(config, null);

        ToolDefinition navigate = provider.getTools().stream()
            .filter(tool -> tool.name().equals("browser_navigate"))
            .findFirst()
            .orElseThrow();
        assertEquals("url", navigate.parametersSchema().get("required").get(0).asText());

        ToolDefinition scroll = provider.getTools().stream()
            .filter(tool -> tool.name().equals("browser_scroll"))
            .findFirst()
            .orElseThrow();
        assertEquals("up", scroll.parametersSchema().at("/properties/direction/enum/0").asText());
        assertEquals("down", scroll.parametersSchema().at("/properties/direction/enum/1").asText());
    }

    @Test
    void missingBrowserRuntimeReturnsInstallInstruction() {
        BrowserConfig config = new BrowserConfig();
        config.setEnabled(true);
        BrowserSessionManager sessionManager = new BrowserSessionManager(config, null, null, null) {
            @Override
            public BrowserSession getOrCreate() {
                throw new PlaywrightException("Executable doesn't exist at /path/to/chromium. Please run playwright install");
            }
        };
        BrowserToolProvider provider = new BrowserToolProvider(config, sessionManager);

        ToolResult result = provider.handleSnapshot(Map.of());

        assertFalse(result.success());
        assertFalse(result.retryable());
        assertTrue(result.error().contains("husky browser install"));
        assertTrue(result.suggestedFix().contains("husky browser install"));
    }
}
