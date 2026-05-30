package io.github.huskyagent.domain.runtime;

import java.util.concurrent.Future;

public interface RunCancellationRegistry {
    boolean isCancelled(RunHandle handle);

    void registerToolFuture(RunHandle handle, Future<?> future);

    void unregisterToolFuture(RunHandle handle, Future<?> future);
}
