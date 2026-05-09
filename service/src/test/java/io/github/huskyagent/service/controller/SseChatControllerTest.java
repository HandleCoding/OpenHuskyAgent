package io.github.huskyagent.service.controller;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.channel.ChannelRuntimeQueueKeyFactory;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.chatbot.ChatbotConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SseChatControllerTest {

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void missingSessionIdCreatesNewSession() {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        SseChatController controller = newController(runtime);

        controller.chat(new SseChatController.ChatRequest("hello", null), null);

        RuntimeExecutionRequest request = runtime.captured.get();
        assertNotNull(request);
        assertTrue(request.isForceNewSession());
        assertNull(request.getInbound().getRequestedSessionId());
    }

    @Test
    void blankSessionIdCreatesNewSession() {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        SseChatController controller = newController(runtime);

        controller.chat(new SseChatController.ChatRequest("hello", "  "), null);

        RuntimeExecutionRequest request = runtime.captured.get();
        assertNotNull(request);
        assertTrue(request.isForceNewSession());
        assertNull(request.getInbound().getRequestedSessionId());
    }

    @Test
    void existingSessionIdUsesResumePath() {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        SseChatController controller = newController(runtime);

        controller.chat(new SseChatController.ChatRequest("hello", "existing-session"), null);

        RuntimeExecutionRequest request = runtime.captured.get();
        assertNotNull(request);
        assertFalse(request.isForceNewSession());
        assertEquals("existing-session", request.getInbound().getRequestedSessionId());
    }

    private SseChatController newController(RecordingRuntimeExecutionService runtime) {
        PrincipalContext.set(Principal.builder()
                .id("api:demo-user")
                .displayName("demo-user")
                .channelType(ChannelType.HTTP)
                .build());
        ChatbotConfig config = new ChatbotConfig();
        config.setEnabled(true);
        return new SseChatController(
                config,
                new SseChannelAdapter(new NoopChannelEventBus()),
                runtime,
                new ChannelInboundQueue(),
                new FixedQueueKeyFactory(),
                new FakeSceneRouter(),
                Runnable::run
        );
    }

    private static class NoopChannelEventBus implements ChannelEventBus {
        @Override
        public void publish(ChannelEvent event) {}

        @Override
        public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}

        @Override
        public void unsubscribe(String channelName) {}

        @Override
        public void streamToken(String sessionId, String token, boolean reasoning) {}

        @Override
        public void subscribeTokens(String channelName, TokenSubscriber subscriber) {}

        @Override
        public void unsubscribeTokens(String channelName) {}
    }

    private static class RecordingRuntimeExecutionService extends RuntimeExecutionService {
        private final AtomicReference<RuntimeExecutionRequest> captured = new AtomicReference<>();

        RecordingRuntimeExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            captured.set(request);
            return RuntimeExecutionResult.rejected(ChatResult.success("ok", "session-1", false));
        }
    }

    private static class FakeSceneRouter extends ChannelSceneRouter {
        FakeSceneRouter() {
            super(null, null);
        }

        @Override
        public EffectiveChannelRoute resolve(InboundMessage inbound) {
            return new EffectiveChannelRoute("assistant", null, EffectiveChannelRoute.Source.SCENE_DEFAULT);
        }
    }

    private static class FixedQueueKeyFactory extends ChannelRuntimeQueueKeyFactory {
        FixedQueueKeyFactory() {
            super(null, null);
        }

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            return "test-http-chat";
        }
    }
}