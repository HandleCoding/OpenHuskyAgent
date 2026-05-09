package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class DefaultChannelEventBus implements ChannelEventBus {

    private static final int QUEUE_CAPACITY = 4096;
    private static final long ENQUEUE_TIMEOUT_MS = 10;

    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenSubscription> tokenSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChannelLane> lanes = new ConcurrentHashMap<>();

    // ── 生命周期事件 ──────────────────────────────────────────────────────────

    @Override
    public void publish(ChannelEvent event) {
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            Subscription sub = entry.getValue();
            if (sub.eventFilter == null || sub.eventFilter.isEmpty() || sub.eventFilter.contains(event.type())) {
                String channelName = entry.getKey();
                lane(channelName).submit(() -> {
                    try {
                        sub.subscriber.onEvent(event);
                    } catch (Exception e) {
                        log.error("Channel '{}' failed to handle event {}: {}",
                                channelName, event.type(), e.getMessage(), e);
                    }
                });
            }
        }
    }

    @Override
    public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {
        subscriptions.put(channelName, new Subscription(eventFilter, subscriber));
        lane(channelName);
        log.info("ChannelEventBus: channel '{}' subscribed (events={})",
                channelName, eventFilter != null ? eventFilter : "ALL");
    }

    @Override
    public void unsubscribe(String channelName) {
        subscriptions.remove(channelName);
        removeLaneIfUnused(channelName);
        log.info("ChannelEventBus: channel '{}' unsubscribed", channelName);
    }

    // ── Token 流（高频，直连，不经 Hook）────────────────────────────────────

    @Override
    public void streamToken(String sessionId, String token, boolean reasoning) {
        for (Map.Entry<String, TokenSubscription> entry : tokenSubscriptions.entrySet()) {
            String channelName = entry.getKey();
            TokenSubscription sub = entry.getValue();
            lane(channelName).submit(() -> {
                try {
                    sub.subscriber.onToken(sessionId, token, reasoning);
                } catch (Exception e) {
                    log.error("Channel '{}' failed to handle token: {}",
                            channelName, e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void subscribeTokens(String channelName, TokenSubscriber subscriber) {
        tokenSubscriptions.put(channelName, new TokenSubscription(subscriber));
        lane(channelName);
        log.info("ChannelEventBus: channel '{}' subscribed tokens", channelName);
    }

    @Override
    public void unsubscribeTokens(String channelName) {
        tokenSubscriptions.remove(channelName);
        removeLaneIfUnused(channelName);
        log.info("ChannelEventBus: channel '{}' unsubscribed tokens", channelName);
    }

    private ChannelLane lane(String channelName) {
        return lanes.computeIfAbsent(channelName, ChannelLane::new);
    }

    private void removeLaneIfUnused(String channelName) {
        if (subscriptions.containsKey(channelName) || tokenSubscriptions.containsKey(channelName)) {
            return;
        }
        ChannelLane lane = lanes.remove(channelName);
        if (lane != null) {
            lane.shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        lanes.values().forEach(ChannelLane::shutdown);
        lanes.clear();
    }

    private static class ChannelLane {
        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

        private final String channelName;
        private final ThreadPoolExecutor executor;

        private ChannelLane(String channelName) {
            this.channelName = channelName;
            this.executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "channel-event-" + channelName + "-" + THREAD_COUNTER.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private void submit(Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                if (!executor.isShutdown() && offerWithTimeout(task)) {
                    return;
                }
                if (!executor.isShutdown()) {
                    task.run();
                }
            }
        }

        private boolean offerWithTimeout(Runnable task) {
            try {
                return executor.getQueue().offer(task, ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void shutdown() {
            executor.shutdownNow();
            log.debug("ChannelEventBus: channel '{}' dispatcher stopped", channelName);
        }
    }

    private record Subscription(Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}
    private record TokenSubscription(TokenSubscriber subscriber) {}
}
