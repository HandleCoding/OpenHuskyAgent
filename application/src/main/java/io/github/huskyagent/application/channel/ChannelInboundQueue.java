package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Slf4j
@Component
public class ChannelInboundQueue {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public CompletableFuture<ChatResult> enqueue(String key, Supplier<ChatResult> task, Executor executor) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(executor, "executor");
        String queueKey = normalizeKey(key);
        CompletableFuture<ChatResult> result = new CompletableFuture<>();
        CompletableFuture<Void> newTail = tails.compute(queueKey, (ignored, currentTail) -> {
            CompletableFuture<Void> previous = currentTail != null ? currentTail : CompletableFuture.completedFuture(null);
            return previous.handle((ok, error) -> null)
                    .thenRunAsync(() -> runTask(queueKey, task, result), executor);
        });
        newTail.whenComplete((ok, error) -> tails.remove(queueKey, newTail));
        return result;
    }

    int activeKeyCount() {
        return tails.size();
    }

    private void runTask(String queueKey, Supplier<ChatResult> task, CompletableFuture<ChatResult> result) {
        try {
            log.debug("Channel inbound queue task started: key={}", queueKey);
            result.complete(task.get());
        } catch (Throwable t) {
            result.completeExceptionally(t);
            throw t;
        } finally {
            log.debug("Channel inbound queue task finished: key={}", queueKey);
        }
    }

    private String normalizeKey(String key) {
        return key == null || key.isBlank() ? "channel-session:unknown" : key;
    }
}
