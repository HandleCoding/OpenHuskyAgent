package io.github.huskyagent;

import io.github.huskyagent.infra.context.ContextStatus;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionContextIntegrationTest extends AbstractIntegrationTest {

    @Test
    @Order(1)
    void testSessionCreation() {
        System.out.println("\n📋 Test: session creation");

        String sessionId = sessionManager.createSession();
        assertNotNull(sessionId, "Session ID should not be null");

        List<?> messages = sessionManager.loadMessages(sessionId);
        assertTrue(messages.isEmpty(), "New session should have no persisted messages");

        System.out.println("✅ Session created successfully:");
        System.out.println("   Session ID: " + sessionId);
        System.out.println("   Initial message count: " + messages.size());
    }

    @Test
    @Order(2)
    void testSessionPersistence() {
        System.out.println("\n📋 Test: session persistence");

        String sessionId = sessionManager.createSession();

        sessionManager.saveUserMessage(sessionId, "Hello");
        sessionManager.saveAssistantMessage(sessionId, "Hi there!");
        sessionManager.saveUserMessage(sessionId, "How are you?");
        sessionManager.saveAssistantMessage(sessionId, "I'm doing well!");

        List<?> messages = sessionManager.loadMessages(sessionId);
        assertTrue(messages.size() >= 4, "Should have at least 4 messages");

        System.out.println("✅ Session persistence succeeded:");
        System.out.println("   Session ID: " + sessionId);
        System.out.println("   Total message count: " + messages.size());

        List<?> sessions = sessionManager.listSessions();
        System.out.println("   All session count: " + sessions.size());
    }

    @Test
    @Order(3)
    void testContextManagerInitialization() {
        System.out.println("\n📋 Test: ContextManager initialization");

        assertNotNull(contextManager, "ContextManager should be injected");

        ContextStatus status = contextManager.getStatus();
        assertNotNull(status, "Status should not be null");
        assertTrue(status.contextLength() > 0, "Context length should be positive");

        System.out.println("✅ ContextManager status:");
        System.out.println("   Context Length: " + status.contextLength());
        System.out.println("   Threshold Tokens: " + status.thresholdTokens());
        System.out.println("   Threshold %: " + contextManager.getConfig().getThresholdPercent());
    }

    @Test
    @Order(4)
    void testTokenEstimation() {
        System.out.println("\n📋 Test: token estimation");

        org.springframework.ai.chat.messages.Message msg1 =
            new org.springframework.ai.chat.messages.UserMessage("this is a test message");
        org.springframework.ai.chat.messages.Message msg2 =
            new org.springframework.ai.chat.messages.AssistantMessage("this is an assistant response");

        List<org.springframework.ai.chat.messages.Message> messages = List.of(msg1, msg2);

        int tokens = contextManager.estimateTokens(messages);
        assertTrue(tokens > 0, "Token estimate should be positive");

        int expectedMin = (msg1.getText().length() + msg2.getText().length()) / 4;
        assertTrue(tokens >= expectedMin, "Tokens should be at least chars/4");

        System.out.println("✅ Token estimation:");
        System.out.println("   Message char count: " + (msg1.getText().length() + msg2.getText().length()));
        System.out.println("   Estimated tokens: " + tokens);
    }

    @Test
    @Order(5)
    void testContextCompressionDecision() {
        System.out.println("\n📋 Test: context compression decision");

        String sessionId = sessionManager.createSession();

        for (int i = 0; i < 10; i++) {
            sessionManager.saveUserMessage(sessionId, "Message " + i);
            sessionManager.saveAssistantMessage(sessionId, "Response " + i);
        }

        List<?> messages = contextManager.loadMessagesForContext(sessionId);
        int tokens = contextManager.estimateTokens((List<org.springframework.ai.chat.messages.Message>) messages);

        ContextStatus status = contextManager.getStatus();
        System.out.println("✅ Context status check:");
        System.out.println("   Message count: " + messages.size());
        System.out.println("   Estimated tokens: " + tokens);
        System.out.println("   Compression threshold: " + status.thresholdTokens());
        System.out.println("   Usage: " + status.usagePercent() + "%");
    }
}
