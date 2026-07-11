package io.github.huskyagent.infra.llm;

import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

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
                mock(ToolCallingManager.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
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
    void getChatModelCachesBySelectionFingerprint() {
        LlmProperties.Provider deepseek = new LlmProperties.Provider();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("ds-key");
        deepseek.setModel("deepseek-chat");
        properties.getProviders().put("deepseek", deepseek);

        var a = registry.getChatModel(ModelSelection.builder()
                .providerId("deepseek")
                .modelName("deepseek-chat")
                .build());
        var b = registry.getChatModel(ModelSelection.builder()
                .providerId("deepseek")
                .modelName("deepseek-chat")
                .build());
        var c = registry.getChatModel(ModelSelection.builder()
                .providerId("deepseek")
                .modelName("deepseek-reasoner")
                .build());

        assertEquals(a, b);
        assertEquals(false, a == c);
    }
}
