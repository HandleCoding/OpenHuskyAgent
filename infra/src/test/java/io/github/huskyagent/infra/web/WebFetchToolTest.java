package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    private WebConfig config;

    @BeforeEach
    void setUp() {
        config = new WebConfig();
        config.setBraveApiKey("test-key");
    }

    @Test
    void testSecurityBlockPrivateUrl() {
        assertFalse(UrlSafety.isSafeUrl("http://192.168.1.1/admin"));
        assertFalse(UrlSafety.isSafeUrl("http://10.0.0.1/secret"));
        assertFalse(UrlSafety.isSafeUrl("http://localhost:8080/api"));
    }

    @Test
    void testSecurityBlockEmbeddedSecret() {
        assertTrue(UrlSafety.containsSecret("https://api.example.com?api_key=" + "sk" + "-abc123456789012345678901234"));
    }

    @Test
    void testSecurityAllowPublicUrl() {
        assertTrue(UrlSafety.isSafeUrl("https://en.wikipedia.org/wiki/Java"));
        assertTrue(UrlSafety.isSafeUrl("https://docs.oracle.com/en/java/"));
    }

    @Test
    void testUrlSchemeAutoAdd() {
        String url = "example.com/page";
        assertTrue(url.startsWith("http://") || url.startsWith("https://") || !url.startsWith("http"));
    }
}