package io.github.huskyagent.domain.graph.node;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

final class TimedToolTask {

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tool-timeout-scheduler");
                t.setDaemon(true);
                return t;
            });

    private TimedToolTask() {
    }

    static <T> CompletableFuture<T> submit(Supplier<T> supplier,
                                           ExecutorService executor,
                                           Duration timeout,
                                           Function<Throwable, T> fallback) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> worker;
        try {
            worker = executor.submit(() -> {
                try {
                    result.complete(supplier.get());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(fallback.apply(e));
            return result;
        }

        ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(() -> {
            if (result.completeExceptionally(new TimeoutException())) {
                worker.cancel(true);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        result.whenComplete((v, e) -> timeoutTask.cancel(false));

        return result.exceptionally(fallback::apply);
    }
}