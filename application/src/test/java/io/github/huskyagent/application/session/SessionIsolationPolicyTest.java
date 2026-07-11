package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionIsolationPolicyTest {

    @Test
    void defaultKeyExcludesTransientSessionAndConnection() {
        DefaultSessionKeyStrategy strategy = new DefaultSessionKeyStrategy();
        IsolationScope left = baseScopeBuilder()
                .sessionId("session-a")
                .build();
        IsolationScope right = baseScopeBuilder()
                .sessionId("session-b")
                .build();

        assertEquals(strategy.buildKey(left), strategy.buildKey(right));
    }

    @Test
    void defaultKeySeparatesSceneAndThread() {
        DefaultSessionKeyStrategy strategy = new DefaultSessionKeyStrategy();
        IsolationScope base = baseScopeBuilder().build();
        IsolationScope otherScene = baseScopeBuilder().agentId("chatbot").build();
        IsolationScope otherThread = baseScopeBuilder().threadId("thread-2").build();

        assertNotEquals(strategy.buildKey(base), strategy.buildKey(otherScene));
        assertNotEquals(strategy.buildKey(base), strategy.buildKey(otherThread));
    }

    @Test
    void accessPolicyRequiresSamePrincipalSceneAndChannel() {
        DefaultSessionAccessPolicy policy = new DefaultSessionAccessPolicy();
        IsolationScope current = baseScopeBuilder().build();

        assertTrue(policy.canResume(current, entity("local:default", "tui", "assistant", "chat-1", "thread-1")));
        assertFalse(policy.canResume(current, entity("other", "tui", "assistant", "chat-1", "thread-1")));
        assertFalse(policy.canResume(current, entity("local:default", "http", "assistant", "chat-1", "thread-1")));
        assertFalse(policy.canResume(current, entity("local:default", "tui", "chatbot", "chat-1", "thread-1")));
        assertFalse(policy.canResume(current, entity("local:default", "tui", "assistant", "chat-2", "thread-1")));
    }

    private IsolationScope.IsolationScopeBuilder baseScopeBuilder() {
        return IsolationScope.builder()
                .sessionId("session")
                .principalId("local:default")
                .channelType("tui")
                .conversationType("thread")
                .platformAccountId("account")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderId("sender")
                .agentId("assistant");
    }

    private SessionEntity entity(String owner, String channel, String scene, String chat, String thread) {
        SessionEntity entity = new SessionEntity();
        entity.setId("existing");
        entity.setUserId(owner);
        entity.setOwnerPrincipalId(owner);
        entity.setChannelType(channel);
        entity.setAgentId(scene);
        entity.setSourceChatId(chat);
        entity.setSourceThreadId(thread);
        return entity;
    }
}
