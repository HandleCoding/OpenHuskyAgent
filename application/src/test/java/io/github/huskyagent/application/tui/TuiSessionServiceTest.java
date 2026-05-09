package io.github.huskyagent.application.tui;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.rpc.JsonRpcDispatcher;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TuiSessionServiceTest {

    private final ExecutorService queueExecutor = Executors.newFixedThreadPool(2);
    private final JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(message -> {});
    private final JsonRpcEventEmitter emitter = new JsonRpcEventEmitter(dispatcher);

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
        queueExecutor.shutdownNow();
    }

    @Test
    void promptsFromSameConnectionRunSequentiallyAndReuseCreatedSession() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        FakeSessionResolver sessionResolver = new FakeSessionResolver("session-1");
        TuiSessionService service = newService(runtime, sessionResolver, "conn-1");

        CompletableFuture<ChatResult> first = CompletableFuture.supplyAsync(() -> service.submitPrompt("first", emitter));
        assertTrue(runtime.firstStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<ChatResult> second = CompletableFuture.supplyAsync(() -> service.submitPrompt("second", emitter));
        assertFalse(runtime.secondStarted.await(150, TimeUnit.MILLISECONDS));

        runtime.releaseFirst.countDown();
        ChatResult firstResult = first.get(1, TimeUnit.SECONDS);
        ChatResult secondResult = second.get(1, TimeUnit.SECONDS);

        assertTrue(firstResult.success());
        assertTrue(secondResult.success());
        assertNotEquals("Session busy", secondResult.errorMessage());
        assertEquals(List.of("first", "second"), runtime.texts);
        assertEquals(List.of("session-1", "session-1"), runtime.requestedSessionIds);
        assertEquals(1, sessionResolver.createCalls);
    }

    @Test
    void queuedPromptDoesNotEnterRuntimeAfterConnectionCloses() throws Exception {
        RecordingRuntimeExecutionService runtime = new RecordingRuntimeExecutionService();
        TuiSessionService service = newService(runtime, new FakeSessionResolver("session-1"), "conn-2");
        CountDownLatch secondPrepared = new CountDownLatch(1);

        CompletableFuture<ChatResult> first = CompletableFuture.supplyAsync(() -> service.submitPrompt("first", emitter));
        assertTrue(runtime.firstStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<ChatResult> second = CompletableFuture.supplyAsync(() ->
                service.submitPrompt("second", emitter, ignored -> secondPrepared.countDown()));
        assertTrue(secondPrepared.await(1, TimeUnit.SECONDS));

        service.close();
        runtime.releaseFirst.countDown();

        assertTrue(first.get(1, TimeUnit.SECONDS).success());
        ChatResult secondResult = second.get(1, TimeUnit.SECONDS);
        assertFalse(secondResult.success());
        assertEquals("Connection closed", secondResult.errorMessage());
        assertEquals(List.of("first"), runtime.texts);
    }

    private TuiSessionService newService(RecordingRuntimeExecutionService runtime,
                                         FakeSessionResolver sessionResolver,
                                         String connectionId) {
        return new TuiSessionService(runtime, sessionResolver, null,
                new ChannelInboundQueue(), queueExecutor, connectionId);
    }

    private static RuntimeScope scope(String sessionId) {
        SceneConfig scene = new SceneConfig();
        scene.setSceneId("assistant");
        RuntimePolicy runtimePolicy = RuntimePolicy.builder()
                .sceneId("assistant")
                .capabilityView(CapabilityView.builder().build())
                .build();
        return RuntimeScope.builder()
                .sessionId(sessionId)
                .principal(Principal.builder().id("local:default").channelType(ChannelType.TUI).build())
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TUI)
                        .conversationType(ConversationType.DIRECT)
                        .connectionId("conn")
                        .build())
                .runtimePolicy(runtimePolicy)
                .workingDirectory(Path.of("/tmp/work"))
                .build();
    }

    private static class RecordingRuntimeExecutionService extends RuntimeExecutionService {
        private final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        private final List<String> requestedSessionIds = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicReference<Throwable> firstWaitFailure = new AtomicReference<>();

        RecordingRuntimeExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            String text = request.getInbound().getText();
            String sessionId = request.getInbound().getRequestedSessionId();
            texts.add(text);
            requestedSessionIds.add(sessionId);
            if ("first".equals(text)) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("first prompt was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    firstWaitFailure.set(e);
                    return RuntimeExecutionResult.executed(ChatResult.failure(e.getMessage()), scope(sessionId));
                } catch (Throwable t) {
                    firstWaitFailure.set(t);
                    return RuntimeExecutionResult.executed(ChatResult.failure(t.getMessage()), scope(sessionId));
                }
            }
            if ("second".equals(text)) {
                secondStarted.countDown();
            }
            Throwable failure = firstWaitFailure.get();
            if (failure != null) {
                return RuntimeExecutionResult.executed(ChatResult.failure(failure.getMessage()), scope(sessionId));
            }
            return RuntimeExecutionResult.executed(ChatResult.success("ok:" + text, sessionId, false), scope(sessionId));
        }
    }

    private static class FakeSessionResolver extends SessionResolver {
        private final String sessionId;
        private int createCalls;

        FakeSessionResolver(String sessionId) {
            super(null, null, null, null, null, null, null, null, null);
            this.sessionId = sessionId;
        }

        @Override
        public RuntimeScope createSession(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
            createCalls++;
            return scope(sessionId);
        }
    }
}
