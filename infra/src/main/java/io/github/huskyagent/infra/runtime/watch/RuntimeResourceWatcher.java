package io.github.huskyagent.infra.runtime.watch;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeResourceWatcher {

    private static final long DEBOUNCE_MS = 500;

    private final List<RuntimeResourceReloadHandler> handlers;
    private final RuntimeReloadCoordinator coordinator;

    private final Map<WatchKey, Path> watchRoots = new ConcurrentHashMap<>();
    private final Map<RuntimeResourceType, Set<Path>> pendingChanges = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "runtime-resource-watch-debounce");
        t.setDaemon(true);
        return t;
    });

    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private ScheduledFuture<?> pendingDispatch;

    @PostConstruct
    public void start() {
        if (handlers.isEmpty()) {
            log.info("RuntimeResourceWatcher disabled: no handlers registered");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (RuntimeResourceReloadHandler handler : handlers) {
                registerDescriptor(handler.descriptor());
            }
        } catch (IOException e) {
            log.warn("Failed to start RuntimeResourceWatcher: {}", e.getMessage());
            closeWatchService();
            return;
        }

        watcherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "runtime-resource-watch-loop");
            t.setDaemon(true);
            return t;
        });
        watcherExecutor.submit(this::watchLoop);
        log.info("RuntimeResourceWatcher started with {} handlers", handlers.size());
    }

    @PreDestroy
    public void stop() {
        if (pendingDispatch != null) {
            pendingDispatch.cancel(false);
        }
        debounceExecutor.shutdownNow();
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
        closeWatchService();
    }

    private void registerDescriptor(RuntimeResourceDescriptor descriptor) throws IOException {
        for (Path root : descriptor.roots()) {
            if (root == null) {
                continue;
            }
            Path normalized = root.toAbsolutePath().normalize();
            if (descriptor.recursive()) {
                registerRecursively(normalized);
            } else {
                Path parent = Files.isDirectory(normalized) ? normalized : normalized.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    registerDirectory(parent);
                }
            }
        }
    }

    private void registerRecursively(Path root) throws IOException {
        Files.createDirectories(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        watchRoots.put(key, dir);
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDir = watchRoots.get(key);
                if (watchedDir == null) {
                    key.reset();
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!(event.context() instanceof Path context)) {
                        continue;
                    }
                    Path changed = watchedDir.resolve(context).toAbsolutePath().normalize();
                    onPathChanged(changed);
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                        try {
                            registerRecursively(changed);
                        } catch (IOException e) {
                            log.warn("Failed to register created directory {}: {}", changed, e.getMessage());
                        }
                    }
                }
                if (!key.reset()) {
                    watchRoots.remove(key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (Exception e) {
                log.warn("RuntimeResourceWatcher loop error: {}", e.getMessage());
            }
        }
    }

    private void onPathChanged(Path changed) {
        for (RuntimeResourceReloadHandler handler : handlers) {
            RuntimeResourceDescriptor descriptor = handler.descriptor();
            if (matches(descriptor, changed)) {
                pendingChanges.computeIfAbsent(descriptor.type(), key -> ConcurrentHashMap.newKeySet()).add(changed);
            }
        }
        scheduleDispatch();
    }

    private boolean matches(RuntimeResourceDescriptor descriptor, Path changed) {
        for (Path root : descriptor.roots()) {
            if (root == null) {
                continue;
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (descriptor.recursive()) {
                if (changed.startsWith(normalizedRoot)) {
                    return true;
                }
            } else {
                if (changed.equals(normalizedRoot)) {
                    return true;
                }
                Path parent = normalizedRoot.getParent();
                if (parent != null && changed.equals(parent)) {
                    return true;
                }
                if (parent != null && changed.startsWith(parent) && changed.getFileName() != null
                        && normalizedRoot.getFileName() != null
                        && changed.getFileName().equals(normalizedRoot.getFileName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void scheduleDispatch() {
        if (pendingDispatch != null) {
            pendingDispatch.cancel(false);
        }
        pendingDispatch = debounceExecutor.schedule(this::flushChanges, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flushChanges() {
        Map<RuntimeResourceType, Set<Path>> snapshot = new EnumMap<>(RuntimeResourceType.class);
        for (Map.Entry<RuntimeResourceType, Set<Path>> entry : pendingChanges.entrySet()) {
            Set<Path> copy = Set.copyOf(entry.getValue());
            if (!copy.isEmpty()) {
                snapshot.put(entry.getKey(), copy);
            }
        }
        pendingChanges.clear();
        if (!snapshot.isEmpty()) {
            coordinator.onPathsChanged(snapshot);
        }
    }

    private void closeWatchService() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }
}
