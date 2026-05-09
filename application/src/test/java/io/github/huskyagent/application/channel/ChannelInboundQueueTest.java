package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChannelInboundQueueTest {

    @Test
    void sameKeyRunsTasksInOrder() throws Exception {
        ChannelInboundQueue queue = new ChannelInboundQueue();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean(false);

        CompletableFuture<ChatResult> first = queue.enqueue("same", () -> {
            releaseFirst.join();
            return ChatResult.success("first", "s1", false);
        }, executor);
        CompletableFuture<ChatResult> second = queue.enqueue("same", () -> {
            secondStarted.set(true);
            return ChatResult.success("second", "s1", false);
        }, executor);

        Thread.sleep(100);
        assertFalse(secondStarted.get());
        releaseFirst.complete(null);

        assertEquals("first", first.get(1, TimeUnit.SECONDS).content());
        assertEquals("second", second.get(1, TimeUnit.SECONDS).content());
        assertTrue(secondStarted.get());
        executor.shutdownNow();
    }

    @Test
    void differentKeysCanRunConcurrently() throws Exception {
        ChannelInboundQueue queue = new ChannelInboundQueue();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean(false);

        CompletableFuture<ChatResult> first = queue.enqueue("one", () -> {
            release.join();
            return ChatResult.success("one", "s1", false);
        }, executor);
        CompletableFuture<ChatResult> second = queue.enqueue("two", () -> {
            secondStarted.set(true);
            return ChatResult.success("two", "s2", false);
        }, executor);

        assertEquals("two", second.get(1, TimeUnit.SECONDS).content());
        assertTrue(secondStarted.get());
        release.complete(null);
        assertEquals("one", first.get(1, TimeUnit.SECONDS).content());
        executor.shutdownNow();
    }

    @Test
    void failureDoesNotPoisonNextTaskForSameKey() throws Exception {
        ChannelInboundQueue queue = new ChannelInboundQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> events = new CopyOnWriteArrayList<>();

        CompletableFuture<ChatResult> first = queue.enqueue("same", () -> {
            events.add("first");
            throw new IllegalStateException("boom");
        }, executor);
        CompletableFuture<ChatResult> second = queue.enqueue("same", () -> {
            events.add("second");
            return ChatResult.success("ok", "s1", false);
        }, executor);

        assertThrows(Exception.class, () -> first.get(1, TimeUnit.SECONDS));
        assertEquals("ok", second.get(1, TimeUnit.SECONDS).content());
        assertEquals(List.of("first", "second"), events);
        executor.shutdownNow();
    }

    @Test
    void removesKeyAfterTailCompletes() throws Exception {
        ChannelInboundQueue queue = new ChannelInboundQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        CompletableFuture<ChatResult> result = queue.enqueue("same", () -> ChatResult.success("ok", "s1", false), executor);

        assertEquals("ok", result.get(1, TimeUnit.SECONDS).content());
        awaitEmpty(queue);
        assertEquals(0, queue.activeKeyCount());
        executor.shutdownNow();
    }

    private void awaitEmpty(ChannelInboundQueue queue) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000;
        while (queue.activeKeyCount() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
