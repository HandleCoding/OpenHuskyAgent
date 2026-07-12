package io.github.huskyagent.infra.llm;

import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.llm.api.LlmProtocol;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmClientRegistryTest {

    private LlmProperties properties;
    private LlmClientRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.setDefaultProvider("main");
        properties.setProviders(new LinkedHashMap<>());
        registry = new LlmClientRegistry(
                properties,
                new AgentConfig(),
                "https://api.openai.com",
                "test-key",
                "/v1/chat/completions",
                "gpt-default",
                0.7);
        registry.ensureDefaultProvider();
    }

    @Test
    void seedsMainProviderFromSpringDefaults() {
        assertEquals("main", registry.defaultProviderId());
        assertEquals("gpt-default", registry.defaultModelName());
        assertEquals("https://api.openai.com", properties.getProviders().get("main").getBaseUrl());
    }

    @Test
    void resolveSelectionUsesAgentModelOnDefaultProvider() {
        ModelSelection resolved = registry.resolveSelection(
                ModelSelection.builder().modelName("gpt-special").build());

        assertEquals("main", resolved.getProviderId());
        assertEquals("gpt-special", resolved.getModelName());
        assertEquals(0.7, resolved.getTemperature());
    }

    @Test
    void resolveSelectionUsesNamedProvider() {
        LlmProperties.Provider deepseek = new LlmProperties.Provider();
        deepseek.setType("openai-compatible");
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("ds-key");
        deepseek.setModel("deepseek-chat");
        deepseek.setTemperature(0.3);
        properties.getProviders().put("deepseek", deepseek);

        ModelSelection resolved = registry.resolveSelection(
                ModelSelection.builder().providerId("deepseek").build());

        assertEquals("deepseek", resolved.getProviderId());
        assertEquals("deepseek-chat", resolved.getModelName());
        assertEquals(0.3, resolved.getTemperature());
    }

    @Test
    void resolveSelectionFailsOnUnknownProvider() {
        assertThrows(IllegalArgumentException.class, () ->
                registry.resolveSelection(ModelSelection.builder().providerId("missing").modelName("x").build()));
    }

    @Test
    void getTransportCachesByEndpointConfig() {
        LlmProperties.Provider deepseek = new LlmProperties.Provider();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("ds-key");
        deepseek.setModel("deepseek-chat");
        deepseek.setProtocol("openai_chat_completions");
        properties.getProviders().put("deepseek", deepseek);

        LlmTransport a = registry.getTransport(ModelSelection.builder()
                .providerId("deepseek")
                .modelName("deepseek-chat")
                .build());
        LlmTransport b = registry.getTransport(ModelSelection.builder()
                .providerId("deepseek")
                .modelName("deepseek-chat")
                .build());

        assertSame(a, b);
        assertEquals(LlmProtocol.OPENAI_CHAT_COMPLETIONS, a.protocol());
    }
}
