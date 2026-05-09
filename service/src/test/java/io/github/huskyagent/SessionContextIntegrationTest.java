package io.github.huskyagent;

import io.github.huskyagent.infra.context.ContextStatus;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionContextIntegrationTest extends AbstractIntegrationTest {

    @Test
    @Order(1)
    void testSessionCreation() {
        System.out.println("\n📋 测试: Session 创建");

        String sessionId = sessionManager.createSession();
        assertNotNull(sessionId, "Session ID should not be null");

        List<?> messages = sessionManager.loadMessages(sessionId);
        assertTrue(messages.isEmpty(), "New session should have no persisted messages");

        System.out.println("✅ Session 创建成功:");
        System.out.println("   Session ID: " + sessionId);
        System.out.println("   初始消息数: " + messages.size());
    }

    @Test
    @Order(2)
    void testSessionPersistence() {
        System.out.println("\n📋 测试: Session 持久化");

        String sessionId = sessionManager.createSession();

        sessionManager.saveUserMessage(sessionId, "Hello");
        sessionManager.saveAssistantMessage(sessionId, "Hi there!");
        sessionManager.saveUserMessage(sessionId, "How are you?");
        sessionManager.saveAssistantMessage(sessionId, "I'm doing well!");

        List<?> messages = sessionManager.loadMessages(sessionId);
        assertTrue(messages.size() >= 4, "Should have at least 4 messages");

        System.out.println("✅ Session 持久化成功:");
        System.out.println("   Session ID: " + sessionId);
        System.out.println("   总消息数: " + messages.size());

        List<?> sessions = sessionManager.listSessions();
        System.out.println("   所有会话数: " + sessions.size());
    }

    @Test
    @Order(3)
    void testContextManagerInitialization() {
        System.out.println("\n📋 测试: Context Manager 初始化");

        assertNotNull(contextManager, "ContextManager should be injected");

        ContextStatus status = contextManager.getStatus();
        assertNotNull(status, "Status should not be null");
        assertTrue(status.contextLength() > 0, "Context length should be positive");

        System.out.println("✅ Context Manager 状态:");
        System.out.println("   Context Length: " + status.contextLength());
        System.out.println("   Threshold Tokens: " + status.thresholdTokens());
        System.out.println("   Threshold %: " + contextManager.getConfig().getThresholdPercent());
    }

    @Test
    @Order(4)
    void testTokenEstimation() {
        System.out.println("\n📋 测试: Token 估算");

        org.springframework.ai.chat.messages.Message msg1 =
            new org.springframework.ai.chat.messages.UserMessage("这是一个测试消息");
        org.springframework.ai.chat.messages.Message msg2 =
            new org.springframework.ai.chat.messages.AssistantMessage("这是助手响应");

        List<org.springframework.ai.chat.messages.Message> messages = List.of(msg1, msg2);

        int tokens = contextManager.estimateTokens(messages);
        assertTrue(tokens > 0, "Token estimate should be positive");

        int expectedMin = (msg1.getText().length() + msg2.getText().length()) / 4;
        assertTrue(tokens >= expectedMin, "Tokens should be at least chars/4");

        System.out.println("✅ Token 估算:");
        System.out.println("   消息字符数: " + (msg1.getText().length() + msg2.getText().length()));
        System.out.println("   估算 Tokens: " + tokens);
    }

    @Test
    @Order(5)
    void testContextCompressionDecision() {
        System.out.println("\n📋 测试: Context 压缩决策");

        String sessionId = sessionManager.createSession();

        for (int i = 0; i < 10; i++) {
            sessionManager.saveUserMessage(sessionId, "Message " + i);
            sessionManager.saveAssistantMessage(sessionId, "Response " + i);
        }

        List<?> messages = contextManager.loadMessagesForContext(sessionId);
        int tokens = contextManager.estimateTokens((List<org.springframework.ai.chat.messages.Message>) messages);

        ContextStatus status = contextManager.getStatus();
        System.out.println("✅ Context 状态检查:");
        System.out.println("   消息数: " + messages.size());
        System.out.println("   估算 Tokens: " + tokens);
        System.out.println("   压缩阈值: " + status.thresholdTokens());
        System.out.println("   使用率: " + status.usagePercent() + "%");
    }
}
