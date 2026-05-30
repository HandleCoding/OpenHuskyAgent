package io.github.huskyagent.application.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class SessionRunCoordinator implements io.github.huskyagent.domain.runtime.RunCancellationRegistry {

    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sessionGenerations = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> queueGenerations = new ConcurrentHashMap<>();

    public RunHandle registerStart(String sessionId, Thread ownerThread) {
        String normalized = normalizeSessionId(sessionId);
        long generation = sessionGenerations
                .computeIfAbsent(normalized, ignored -> new AtomicLong())
                .incrementAndGet();
        RunHandle handle = new RunHandle(normalized, UUID.randomUUID().toString(), generation);
        activeRuns.put(normalized, new ActiveRun(handle, ownerThread));
        return handle;
    }

    public StopResult interrupt(String sessionId, String reason) {
        String normalized = normalizeSessionId(sessionId);
        ActiveRun run = activeRuns.get(normalized);
        if (run == null) {
            bumpSessionGeneration(normalized);
            return StopResult.none(normalized, reason);
        }
        cancelRun(run, reason);
        activeRuns.remove(normalized, run);
        bumpSessionGeneration(normalized);
        return StopResult.stopped(run.handle(), reason);
    }

    public StopResult expire(String sessionId, String reason) {
        return interrupt(sessionId, reason);
    }

    @Override
    public boolean isCancelled(io.github.huskyagent.domain.runtime.RunHandle handle) {
        return handle != null && isCancelled(new RunHandle(handle.sessionId(), handle.runId(), handle.generation()));
    }

    public boolean isCancelled(RunHandle handle) {
        if (handle == null) {
            return false;
        }
        ActiveRun run = activeRuns.get(handle.sessionId());
        if (run == null || !run.handle().equals(handle)) {
            return true;
        }
        return run.cancelled().get();
    }

    public boolean isCurrent(RunHandle handle) {
        if (handle == null) {
            return true;
        }
        ActiveRun run = activeRuns.get(handle.sessionId());
        return run != null && run.handle().equals(handle) && !run.cancelled().get();
    }

    public void throwIfCancelled(RunHandle handle) {
        if (isCancelled(handle)) {
            throw new RunCancelledException(handle != null ? handle.sessionId() : null);
        }
    }

    public void finishIfCurrent(RunHandle handle) {
        if (handle == null) {
            return;
        }
        activeRuns.computeIfPresent(handle.sessionId(), (ignored, run) -> run.handle().equals(handle) ? null : run);
    }

    @Override
    public void registerToolFuture(io.github.huskyagent.domain.runtime.RunHandle handle, Future<?> future) {
        registerToolFuture(handle != null ? new RunHandle(handle.sessionId(), handle.runId(), handle.generation()) : null, future);
    }

    public void registerToolFuture(RunHandle handle, Future<?> future) {
        if (handle == null || future == null) {
            return;
        }
        ActiveRun run = activeRuns.get(handle.sessionId());
        if (run == null || !run.handle().equals(handle)) {
            future.cancel(true);
            return;
        }
        run.toolFutures().put(future, Boolean.TRUE);
        if (run.cancelled().get()) {
            future.cancel(true);
        }
    }

    @Override
    public void unregisterToolFuture(io.github.huskyagent.domain.runtime.RunHandle handle, Future<?> future) {
        unregisterToolFuture(handle != null ? new RunHandle(handle.sessionId(), handle.runId(), handle.generation()) : null, future);
    }

    public void unregisterToolFuture(RunHandle handle, Future<?> future) {
        if (handle == null || future == null) {
            return;
        }
        ActiveRun run = activeRuns.get(handle.sessionId());
        if (run != null && run.handle().equals(handle)) {
            run.toolFutures().remove(future);
        }
    }

    public long currentQueueGeneration(String queueKey) {
        return queueGenerations.computeIfAbsent(normalizeQueueKey(queueKey), ignored -> new AtomicLong()).get();
    }

    public long bumpQueueGeneration(String queueKey) {
        return queueGenerations.computeIfAbsent(normalizeQueueKey(queueKey), ignored -> new AtomicLong()).incrementAndGet();
    }

    public boolean isQueueGenerationCurrent(String queueKey, long generation) {
        return currentQueueGeneration(queueKey) == generation;
    }

    private long bumpSessionGeneration(String sessionId) {
        return sessionGenerations.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private void cancelRun(ActiveRun run, String reason) {
        if (!run.cancelled().compareAndSet(false, true)) {
            return;
        }
        log.info("Cancelling active run: sessionId={}, runId={}, reason={}",
                run.handle().sessionId(), run.handle().runId(), reason);
        Thread owner = run.ownerThread();
        if (owner != null && owner != Thread.currentThread()) {
            owner.interrupt();
        }
        run.toolFutures().keySet().forEach(future -> future.cancel(true));
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "session:unknown" : sessionId;
    }

    private String normalizeQueueKey(String queueKey) {
        return queueKey == null || queueKey.isBlank() ? "queue:unknown" : queueKey;
    }

    private record ActiveRun(RunHandle handle,
                             Thread ownerThread,
                             AtomicBoolean cancelled,
                             Map<Future<?>, Boolean> toolFutures) {
        ActiveRun(RunHandle handle, Thread ownerThread) {
            this(handle, ownerThread, new AtomicBoolean(false), new ConcurrentHashMap<>());
        }
    }
}
