package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelCapabilities;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRuntimeServiceTest {

    @Test
    void sendsCommandReplyFromRuntimeExecutionService() {
        OutboundMessage reply = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .sessionId("s1")
                .text("ok")
                .build();
        FakeRuntimeExecutionService runtimeExecutionService = new FakeRuntimeExecutionService(
                RuntimeExecutionResult.commandHandled(reply));
        ChannelRuntimeService service = new ChannelRuntimeService(
                runtimeExecutionService,
                new ChannelInboundQueue(),
                new FakeQueueKeyFactory("key"),
                new FakeSceneRouter());
        RecordingAdapter adapter = new RecordingAdapter();

        ChatResult result = service.handleInbound(inbound(), adapter);

        assertTrue(result.success());
        assertSame(reply, adapter.sent.get(0));
        assertNotNull(runtimeExecutionService.request.getCallbacks());
    }

    @Test
    void doesNotSendCommandReplyForRejectedRuntimeResult() {
        FakeRuntimeExecutionService runtimeExecutionService = new FakeRuntimeExecutionService(
                RuntimeExecutionResult.rejected(ChatResult.failure("bad", ChatResult.ErrorCode.PARAM_ERROR)));
        ChannelRuntimeService service = new ChannelRuntimeService(
                runtimeExecutionService,
                new ChannelInboundQueue(),
                new FakeQueueKeyFactory("key"),
                new FakeSceneRouter());
        RecordingAdapter adapter = new RecordingAdapter();

        ChatResult result = service.handleInbound(inbound(), adapter);

        assertFalse(result.success());
        assertTrue(adapter.sent.isEmpty());
    }

    @Test
    void asyncHandleSerializesSameQueueKey() throws Exception {
        BlockingRuntimeExecutionService runtimeExecutionService = new BlockingRuntimeExecutionService();
        ChannelRuntimeService service = new ChannelRuntimeService(
                runtimeExecutionService,
                new ChannelInboundQueue(),
                new FakeQueueKeyFactory("same"),
                new FakeSceneRouter());
        RecordingAdapter adapter = new RecordingAdapter();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CompletableFuture<ChatResult> first = service.handleInboundAsync(inbound(), adapter, executor);
        runtimeExecutionService.awaitStarted(1);
        CompletableFuture<ChatResult> second = service.handleInboundAsync(inbound(), adapter, executor);

        Thread.sleep(100);
        assertEquals(1, runtimeExecutionService.startedCount());
        runtimeExecutionService.releaseOne();
        assertTrue(first.get(1, TimeUnit.SECONDS).success());
        runtimeExecutionService.awaitStarted(2);
        runtimeExecutionService.releaseOne();
        assertTrue(second.get(1, TimeUnit.SECONDS).success());
        executor.shutdownNow();
    }

    @Test
    void asyncHandleAllowsDifferentQueueKeysConcurrently() throws Exception {
        BlockingRuntimeExecutionService runtimeExecutionService = new BlockingRuntimeExecutionService();
        CyclingQueueKeyFactory keyFactory = new CyclingQueueKeyFactory("one", "two");
        ChannelRuntimeService service = new ChannelRuntimeService(
                runtimeExecutionService,
                new ChannelInboundQueue(),
                keyFactory,
                new FakeSceneRouter());
        RecordingAdapter adapter = new RecordingAdapter();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CompletableFuture<ChatResult> first = service.handleInboundAsync(inbound(), adapter, executor);
        CompletableFuture<ChatResult> second = service.handleInboundAsync(inbound(), adapter, executor);

        runtimeExecutionService.awaitStarted(2);
        assertEquals(2, runtimeExecutionService.startedCount());
        runtimeExecutionService.releaseOne();
        runtimeExecutionService.releaseOne();
        assertTrue(first.get(1, TimeUnit.SECONDS).success());
        assertTrue(second.get(1, TimeUnit.SECONDS).success());
        executor.shutdownNow();
    }

    private static InboundMessage inbound() {
        return InboundMessage.builder()
                .text("hello")
                .principal(Principal.builder().id("user-1").channelType(ChannelType.FEISHU).build())
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.FEISHU)
                        .conversationType(ConversationType.DIRECT)
                        .build())
                .build();
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

    private static class FakeQueueKeyFactory extends ChannelRuntimeQueueKeyFactory {
        private final String key;

        FakeQueueKeyFactory(String key) {
            super(null, null);
            this.key = key;
        }

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            return key;
        }
    }

    private static class CyclingQueueKeyFactory extends ChannelRuntimeQueueKeyFactory {
        private final String[] keys;
        private final AtomicInteger index = new AtomicInteger();

        CyclingQueueKeyFactory(String... keys) {
            super(null, null);
            this.keys = keys;
        }

        @Override
        public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
            int i = index.getAndIncrement();
            return keys[Math.min(i, keys.length - 1)];
        }
    }

    private static class BlockingRuntimeExecutionService extends RuntimeExecutionService {
        private final AtomicInteger started = new AtomicInteger();
        private final java.util.concurrent.Semaphore releases = new java.util.concurrent.Semaphore(0);

        BlockingRuntimeExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            started.incrementAndGet();
            try {
                releases.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return RuntimeExecutionResult.executed(ChatResult.success("ok", "s1", false), null);
        }

        int startedCount() {
            return started.get();
        }

        void releaseOne() {
            releases.release();
        }

        void awaitStarted(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 1000;
            while (started.get() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expected, started.get());
        }
    }

    private static class FakeRuntimeExecutionService extends RuntimeExecutionService {
        private final RuntimeExecutionResult result;
        RuntimeExecutionRequest request;

        FakeRuntimeExecutionService(RuntimeExecutionResult result) {
            super(null, null, null, null, null, null, null);
            this.result = result;
        }

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
            this.request = request;
            return result;
        }
    }

    private static class RecordingAdapter implements ChannelAdapter {
        final List<OutboundMessage> sent = new ArrayList<>();

        @Override
        public ChannelType channelType() {
            return ChannelType.FEISHU;
        }

        @Override
        public ChannelCapabilities capabilities() {
            return ChannelCapabilities.basic();
        }

        @Override
        public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
            return null;
        }

        @Override
        public void send(OutboundMessage message) {
            sent.add(message);
        }

        @Override
        public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            return ApprovalDecision.builder().approved(false).build();
        }

        @Override
        public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
            return ClarifyDecision.builder().answer("").build();
        }
    }
}