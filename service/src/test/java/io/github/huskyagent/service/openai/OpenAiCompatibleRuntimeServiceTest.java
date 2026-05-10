package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.channel.ChannelRuntimeQueueKeyFactory;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.scene.ConfigSceneResolver;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleRuntimeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void mapsModelToSceneAndUsesStatelessModeWhenNoHint() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        OpenAiCompatibleRuntimeService service = newService(runtime);
        OpenAiChatCompletionRequest request = request("assistant");

        service.execute(request, null, new OpenAiCollectingRuntimeCallbacks());

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertNotNull(captured);
        assertEquals("assistant", captured.getInbound().getSceneId());
        assertEquals(ChannelType.HTTP, captured.getInbound().getChannelIdentity().getChannelType());
        assertEquals("openai-compatible", captured.getInbound().getChannelIdentity().getPlatformAccountId());
        assertFalse(captured.isForceNewSession());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATELESS, captured.persistenceModeOrDefault());
        assertTrue(captured.isStateless());
    }

    @Test
    void statelessRequestsUsePerRequestQueueKey() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        RecordingQueueKeyFactory queueKeyFactory = new RecordingQueueKeyFactory();
        OpenAiCompatibleRuntimeService service = newService(runtime, new OpenAiChannelAdapter(new RecordingChannelEventBus()), queueKeyFactory);

        service.execute(request("assistant"), null, new OpenAiCollectingRuntimeCallbacks());

        assertNull(queueKeyFactory.inbound);
    }

    @Test
    void explicitSessionHintResumesSession() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        OpenAiCompatibleRuntimeService service = newService(runtime);

        service.execute(request("assistant"), "session-1", new OpenAiCollectingRuntimeCallbacks());

        RuntimeExecutionRequest captured = runtime.captured.get();
        assertFalse(captured.isForceNewSession());
        assertEquals("session-1", captured.getInbound().getRequestedSessionId());
        assertEquals(RuntimeExecutionRequest.PersistenceMode.STATEFUL, captured.persistenceModeOrDefault());
    }

    @Test
    void streamingEarlySuccessCompletesEmitter() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        OpenAiCompatibleRuntimeService service = newService(runtime);
        RecordingStreamingCallbacks callbacks = new RecordingStreamingCallbacks();

        service.stream(request("assistant"), null, callbacks);

        assertEquals("command-ok", callbacks.content);
        assertTrue(callbacks.finished);
    }

    @Test
    void streamingRegistersTokenAdapterWithResolvedSession() throws Exception {
        RecordingChannelEventBus eventBus = new RecordingChannelEventBus();
        OpenAiChannelAdapter channelAdapter = new OpenAiChannelAdapter(eventBus);
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService(eventBus, channelAdapter);
        OpenAiCompatibleRuntimeService service = newService(runtime, channelAdapter);
        RecordingStreamingCallbacks callbacks = new RecordingStreamingCallbacks();

        service.stream(request("assistant"), null, callbacks);

        assertTrue(callbacks.finishedLatch.await(1, TimeUnit.SECONDS));
        assertEquals("token-ok", callbacks.content);
        assertTrue(callbacks.finished);
    }

    @Test
    void streamingFallsBackToFinalContentWhenNoTokensArrive() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService(ChatResult.success("final-ok", "session-result", false));
        OpenAiCompatibleRuntimeService service = newService(runtime);
        RecordingStreamingCallbacks callbacks = new RecordingStreamingCallbacks();

        service.stream(request("assistant"), null, callbacks);

        assertTrue(callbacks.finishedLatch.await(1, TimeUnit.SECONDS));
        assertEquals("final-ok", callbacks.content);
        assertTrue(callbacks.finished);
    }

    @Test
    void nonStreamingRuntimeExceptionReturnsFailureResult() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        runtime.failWith = new IllegalStateException("runtime exploded");
        OpenAiCompatibleRuntimeService service = newService(runtime);

        RuntimeExecutionResult result = service.execute(request("assistant"), null, new OpenAiCollectingRuntimeCallbacks());

        assertNotNull(result.chatResult());
        assertFalse(result.chatResult().success());
        assertEquals("runtime exploded", result.chatResult().errorMessage());
    }

    private OpenAiCompatibleRuntimeService newService(RecordingRuntimeExecutionService runtime) {
        return newService(runtime, new OpenAiChannelAdapter(new RecordingChannelEventBus()), new FixedQueueKeyFactory());
    }

    private OpenAiCompatibleRuntimeService newService(RecordingRuntimeExecutionService runtime, OpenAiChannelAdapter channelAdapter) {
        return newService(runtime, channelAdapter, new FixedQueueKeyFactory());
    }

    private OpenAiCompatibleRuntimeService newService(RecordingRuntimeExecutionService runtime, OpenAiChannelAdapter channelAdapter,
                                                      ChannelRuntimeQueueKeyFactory queueKeyFactory) {
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        ConfigSceneResolver sceneResolver = new ConfigSceneResolver();
        LinkedHashMap<String, ConfigSceneResolver.SceneProperties> configs = new LinkedHashMap<>();
        configs.put("assistant", new ConfigSceneResolver.SceneProperties());
        sceneResolver.setConfigs(configs);
        OpenAiModelCatalog modelCatalog = new OpenAiModelCatalog(sceneResolver, properties);
        return new OpenAiCompatibleRuntimeService(
                runtime,
                new ChannelInboundQueue(),
                queueKeyFactory,
                new EchoSceneRouter(),
                properties,
                modelCatalog,
                new OpenAiPromptMapper(),
                channelAdapter,
                Runnable::run
        );
    }

    private OpenAiChatCompletionRequest request(String model) throws Exception {
        return objectMapper.readValue("""
                {"model":"%s","messages":[{"role":"user","content":"hello"}]}
                """.formatted(model), OpenAiChatCompletionRequest.class);
    }

    private static class RecordingRuntimeExecutionService extends RuntimeExecutionService {
        private final AtomicReference<RuntimeExecutionRequest> captured = new AtomicReference<>();
        private final RecordingChannelEventBus eventBus;
        private final OpenAiChannelAdapter channelAdapter;
        private final ChatResult executedResult;
        private RuntimeException failWith;

        RecordingRuntimeExecutionService() {
            this(null, null, null);
        }

        RecordingRuntimeExecutionService(ChatResult executedResult) {
            this(null, null, executedResult);
        }

        RecordingRuntimeExecutionService(RecordingChannelEventBus eventBus, OpenAiChannelAdapter channelAdapter) {
            this(eventBus, channelAdapter, null);
        }

        RecordingRuntimeExecutionService(RecordingChannelEventBus eventBus, OpenAiChannelAdapter channelAdapter,
                                         ChatResult executedResult) {
            super(null, null, null, null, null, null, null);
            this.eventBus = eventBus;
            this.channelAdapter = channelAdapter;
            this.executedResult = executedResult;
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            captured.set(request);
            if (failWith != null) {
                throw failWith;
            }
            if (eventBus != null) {
                request.getCallbacks().started(io.github.huskyagent.application.session.RuntimeScope.builder()
                        .sessionId("session-result")
                        .build());
                assertTrue(channelAdapter.hasCallback("session-result"));
                eventBus.streamToken("session-result", "token-ok", false);
                request.getCallbacks().completed(null, ChatResult.success(null, "session-result", false));
                return RuntimeExecutionResult.executed(ChatResult.success(null, "session-result", false), null);
            }
            if (executedResult != null) {
                request.getCallbacks().started(io.github.huskyagent.application.session.RuntimeScope.builder()
                        .sessionId("session-result")
                        .build());
                request.getCallbacks().completed(null, executedResult);
                return RuntimeExecutionResult.executed(executedResult, null);
            }
            return RuntimeExecutionResult.rejected(ChatResult.success("command-ok", "session-result", false));
        }
    }

    private static class RecordingStreamingCallbacks extends OpenAiStreamingRuntimeCallbacks {
        private String content;
        private boolean finished;
        private final CountDownLatch finishedLatch = new CountDownLatch(1);

        RecordingStreamingCallbacks() {
            super(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                    new ObjectMapper(), new OpenAiResponseMapper(), "chatcmpl-test", 123L, "assistant");
        }

        @Override
        void emitContent(String content) {
            if (content != null) {
                this.content = content;
            }
            super.emitContent(content);
        }

        @Override
        void emitToken(String token, boolean reasoning) {
            this.content = token;
            super.emitToken(token, reasoning);
        }

        @Override
        void finish() {
            this.finished = true;
            finishedLatch.countDown();
        }
    }

    private static class EchoSceneRouter extends ChannelSceneRouter {
        EchoSceneRouter() {
            super(null, null);
        }

        @Override
        public EffectiveChannelRoute resolve(InboundMessage inbound) {
            return new EffectiveChannelRoute(inbound.getSceneId(), null, EffectiveChannelRoute.Source.EXPLICIT);
        }
    }

    private static class FixedQueueKeyFactory extends ChannelRuntimeQueueKeyFactory {
        FixedQueueKeyFactory() {
            super(null, null);
        }

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            return "test-openai-compatible";
        }
    }

    private static class RecordingQueueKeyFactory extends FixedQueueKeyFactory {
        private InboundMessage inbound;

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            this.inbound = inbound;
            return super.keyFor(inbound, route);
        }
    }

    private static class RecordingChannelEventBus implements ChannelEventBus {
        private TokenSubscriber tokenSubscriber;

        @Override
        public void publish(ChannelEvent event) {}

        @Override
        public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}

        @Override
        public void unsubscribe(String channelName) {}

        @Override
        public void streamToken(String sessionId, String token, boolean reasoning) {
            tokenSubscriber.onToken(sessionId, token, reasoning);
        }

        @Override
        public void subscribeTokens(String channelName, TokenSubscriber subscriber) {
            this.tokenSubscriber = subscriber;
        }

        @Override
        public void unsubscribeTokens(String channelName) {}
    }
}
