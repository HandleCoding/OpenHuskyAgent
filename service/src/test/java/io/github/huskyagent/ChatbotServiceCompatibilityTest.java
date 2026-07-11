package io.github.huskyagent;

import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.session.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("live-api")
class ChatbotServiceCompatibilityTest extends AbstractIntegrationTest {

    private static final Properties MAIN_APP_PROPERTIES = SpringAiChatModelCompatibilityTest.loadMainApplicationPropertiesForTests();

    @Autowired
    private SessionResolver sessionResolver;

    @AfterEach
    void tearDown() {
        SessionContext.clear();
    }

    @DynamicPropertySource
    static void overrideOpenAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> requireMainProperty("spring.ai.openai.api-key"));
        registry.add("spring.ai.openai.base-url", () -> requireMainProperty("spring.ai.openai.base-url"));
        registry.add("spring.ai.openai.chat.options.model", () -> requireMainProperty("spring.ai.openai.chat.options.model"));
        registry.add("spring.ai.openai.chat.options.temperature",
                () -> MAIN_APP_PROPERTIES.getProperty("spring.ai.openai.chat.options.temperature", "0.7"));
        registry.add("chatbot.enabled", () -> true);
        registry.add("mcp.enabled", () -> false);
    }

    @Test
    void chatbotSessionResolverReturnsCompleteRuntimeScopeWithoutBindingContext() {
        Principal principal = Principal.builder()
                .id("api:test-user")
                .displayName("test-user")
                .channelType(ChannelType.HTTP)
                .build();

        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.HTTP)
                .conversationType(ConversationType.DIRECT)
                .senderId("test-user")
                .build();

        RuntimeScope scope = sessionResolver.createSession(principal, channelIdentity, "chatbot");

        assertDoesNotThrow(scope::requireCompleteForExecution);
        assertEquals("chatbot", scope.getRuntimePolicy().getAgentId());
        assertNotNull(scope.getRuntimePolicy());
        assertNull(SessionContext.getScope(), "SessionResolver should not bind execution context");
    }

    static String requireMainProperty(String key) {
        String value = MAIN_APP_PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing property in main application.yml: " + key);
        }
        return value;
    }
}
