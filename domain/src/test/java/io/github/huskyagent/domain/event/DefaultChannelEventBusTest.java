package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DefaultChannelEventBusTest {

    @Test
    void preservesOrderWithinChannelAcrossTokensAndEvents() throws Exception {
        DefaultChannelEventBus bus = new DefaultChannelEventBus();
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        bus.subscribe("tui", Set.of(HookEvent.LLM_CALL_AFTER), event -> {
            delivered.add("event:" + event.type());
            latch.countDown();
        });
        bus.subscribeTokens("tui", (sessionId, token, reasoning) -> {
            delivered.add("token:" + token);
            latch.countDown();
        });

        bus.streamToken("s1", "a", false);
        bus.publish(new ChannelEvent("s1", HookEvent.LLM_CALL_AFTER, Map.of(), Instant.now()));
        bus.streamToken("s1", "b", false);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("token:a", "event:LLM_CALL_AFTER", "token:b"), delivered);
        bus.shutdown();
    }

    @Test
    void slowSubscriberDoesNotBlockTokenSubmission() throws Exception {
        DefaultChannelEventBus bus = new DefaultChannelEventBus();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        bus.subscribeTokens("slow", (sessionId, token, reasoning) -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long elapsedMs = assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
            long start = System.nanoTime();
            bus.streamToken("s1", "token", false);
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });

        assertTrue(elapsedMs < 100);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        bus.shutdown();
    }

    @Test
    void unsubscribeStopsFurtherDelivery() throws Exception {
        DefaultChannelEventBus bus = new DefaultChannelEventBus();
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);

        bus.subscribeTokens("tui", (sessionId, token, reasoning) -> {
            delivered.add(token);
            first.countDown();
        });

        bus.streamToken("s1", "first", false);
        assertTrue(first.await(2, TimeUnit.SECONDS));
        bus.unsubscribeTokens("tui");
        bus.streamToken("s1", "second", false);
        Thread.sleep(50);

        assertEquals(List.of("first"), delivered);
        bus.shutdown();
    }
}
