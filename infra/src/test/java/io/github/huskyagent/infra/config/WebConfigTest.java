package io.github.huskyagent.infra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebConfig 单元测试
 */
class WebConfigTest {

    @Test
    void testResolveBackendWithBraveKey() {
        WebConfig config = new WebConfig();
        config.setBackend("auto");
        config.setBraveApiKey("test-key");

        assertEquals("brave", config.resolveBackend());
    }

    @Test
    void testResolveBackendWithTavilyKey() {
        WebConfig config = new WebConfig();
        config.setBackend("auto");
        config.setTavilyApiKey("test-key");

        assertEquals("tavily", config.resolveBackend());
    }

    @Test
    void testResolveBackendExplicit() {
        WebConfig config = new WebConfig();
        config.setBackend("brave");

        assertEquals("brave", config.resolveBackend());
    }

    @Test
    void testResolveBackendNone() {
        WebConfig config = new WebConfig();
        config.setBackend("none");

        assertEquals("none", config.resolveBackend());
    }

    @Test
    void testIsBackendAvailableBrave() {
        WebConfig config = new WebConfig();
        config.setBraveApiKey("test-key");

        assertTrue(config.isBackendAvailable());
    }

    @Test
    void testIsBackendAvailableNone() {
        WebConfig config = new WebConfig();
        config.setBackend("none");

        assertFalse(config.isBackendAvailable());
    }

    @Test
    void testResolveBraveApiKeyFromConfig() {
        WebConfig config = new WebConfig();
        config.setBraveApiKey("my-key");

        assertEquals("my-key", config.resolveBraveApiKey());
    }

    @Test
    void testResolveBraveApiKeyEmptyWhenNoEnv() {
        WebConfig config = new WebConfig();
        // 当 config 字段为空时，回退到环境变量；如果环境变量也不存在才返回空
        String envKey = System.getenv("BRAVE_SEARCH_API_KEY");
        String expected = (envKey != null && !envKey.isBlank()) ? envKey : "";
        assertEquals(expected, config.resolveBraveApiKey());
    }

    @Test
    void testDefaultValues() {
        WebConfig config = new WebConfig();

        assertEquals("auto", config.getBackend());
        assertEquals(5, config.getDefaultSearchLimit());
        assertEquals(20, config.getMaxSearchLimit());
        assertEquals(2_000_000, config.getMaxFetchSizeBytes());
        assertEquals(5000, config.getSummarizeThresholdChars());
        assertEquals(5000, config.getMaxOutputChars());
        assertEquals(30, config.getRequestTimeoutSeconds());
        assertNotNull(config.getProxy());
    }
}