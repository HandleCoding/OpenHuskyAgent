package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.runtime.RunCancellationRegistry;
import io.github.huskyagent.domain.runtime.RunHandle;

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
        return submit(supplier, executor, timeout, fallback, null, null);
    }

    static <T> CompletableFuture<T> submit(Supplier<T> supplier,
                                           ExecutorService executor,
                                           Duration timeout,
                                           Function<Throwable, T> fallback,
                                           RunHandle runHandle,
                                           RunCancellationRegistry runCoordinator) {
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
        CancellableFuture cancelHandle = new CancellableFuture(worker, result);
        if (runCoordinator != null) {
            runCoordinator.registerToolFuture(runHandle, cancelHandle);
        }

        ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(() -> {
            if (result.completeExceptionally(new TimeoutException())) {
                worker.cancel(true);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        result.whenComplete((v, e) -> {
            timeoutTask.cancel(false);
            if (runCoordinator != null) {
                runCoordinator.unregisterToolFuture(runHandle, cancelHandle);
            }
        });

        return result.exceptionally(fallback::apply);
    }

    private record CancellableFuture(Future<?> worker, CompletableFuture<?> result) implements Future<Object> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean completed = result.completeExceptionally(new CancellationException("Run cancelled"));
            boolean cancelled = worker.cancel(mayInterruptIfRunning);
            return completed || cancelled;
        }

        @Override
        public boolean isCancelled() {
            return worker.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone() || worker.isDone();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("Cancellation handle does not expose results");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("Cancellation handle does not expose results");
        }
    }
}