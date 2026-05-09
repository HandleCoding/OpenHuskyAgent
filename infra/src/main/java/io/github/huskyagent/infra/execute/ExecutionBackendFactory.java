package io.github.huskyagent.infra.execute;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Per-session ExecutionBackend lifecycle manager.
 *
 * <p>Creates backends lazily on first tool use, caches them by sessionId,
 * and releases them on SESSION_END or idle TTL expiry.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionBackendFactory {

    private final ExecutionBackendProperties properties;

    private final ConcurrentHashMap<String, BackendEntry> backends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionMeta> sessionMeta = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backend-reaper");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        long interval = Math.max(30, properties.getIdleTtlSeconds() / 2);
        reaper.scheduleAtFixedRate(this::reapIdleBackends, interval, interval, TimeUnit.SECONDS);
        log.info("ExecutionBackendFactory started, idle-ttl={}s", properties.getIdleTtlSeconds());
    }

    /**
     * Register a session's backend config so the factory can create it on first use.
     * Called by SessionResolver after RuntimeScope is built.
     */
    public void registerSession(String sessionId, BackendConfig backendConfig) {
        sessionMeta.put(sessionId, new SessionMeta(backendConfig));
    }

    /**
     * Get or lazily create the ExecutionBackend for a session.
     * If the session already has a live backend (e.g. from a previous turn), reuse it.
     * Falls back to an ephemeral LOCAL backend only when no meta and no existing backend.
     */
    public ExecutionBackend getForSession(String sessionId) {
        // Reuse existing live backend even if sessionMeta was unregistered after SESSION_END
        BackendEntry existing = backends.get(sessionId);
        if (existing != null && existing.backend.isAlive()) {
            existing.touch();
            return existing.backend;
        }
        SessionMeta meta = sessionMeta.get(sessionId);
        if (meta == null) {
            return getOrCreate(sessionId, BackendConfig.builder().type("local").build());
        }
        return getOrCreate(sessionId, meta.backendConfig());
    }

    /**
     * Touch a session to record activity, even when no tool was called.
     * Call this at the start of each conversation turn so pure-chat turns
     * also reset the idle TTL.
     */
    public void touchSession(String sessionId) {
        BackendEntry entry = backends.get(sessionId);
        if (entry != null) {
            entry.touch();
        }
    }

    /**
     * Unregister session meta on SESSION_END.
     * The backend itself is kept alive and will be reaped by the idle TTL reaper,
     * so the container survives across multiple turns of the same session.
     */
    public void unregisterSession(String sessionId) {
        sessionMeta.remove(sessionId);
    }

    /**
     * Forcibly release a backend immediately (e.g. on application shutdown or explicit eviction).
     */
    public void release(String sessionId) {
        sessionMeta.remove(sessionId);
        BackendEntry entry = backends.remove(sessionId);
        if (entry != null) {
            safeRelease(entry.backend);
            log.info("Released backend for session={}", sessionId);
        }
    }

    private ExecutionBackend getOrCreate(String sessionId, BackendConfig cfg) {
        BackendEntry entry = backends.compute(sessionId, (sid, existing) -> {
            if (existing != null && existing.backend.isAlive()) {
                existing.touch();
                return existing;
            }
            if (existing != null) {
                safeRelease(existing.backend);
            }
            BackendConfig effectiveConfig = cfg != null ? cfg : BackendConfig.builder().type("local").build();
            ExecutionBackend backend = createBackend(effectiveConfig);
            log.info("Created {} backend for session={}", effectiveConfig.getType(), sessionId);
            return new BackendEntry(backend);
        });
        return entry.backend;
    }

    private ExecutionBackend createBackend(BackendConfig cfg) {
        return switch (cfg.getType()) {
            case "docker" -> new DockerBackend(cfg);
            case "ssh"    -> new SshBackend(cfg);
            default       -> new LocalBackend(cfg);
        };
    }

    private void reapIdleBackends() {
        long ttlMs = properties.getIdleTtlSeconds() * 1000L;
        long now = System.currentTimeMillis();
        backends.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUsedMs > ttlMs) {
                safeRelease(e.getValue().backend);
                log.info("Reaped idle backend for session={}", e.getKey());
                return true;
            }
            return false;
        });
    }

    private void safeRelease(ExecutionBackend backend) {
        try {
            backend.release();
        } catch (Exception e) {
            log.warn("Error releasing backend: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        reaper.shutdownNow();
        backends.forEach((sid, entry) -> safeRelease(entry.backend));
        backends.clear();
    }

    private static class BackendEntry {
        final ExecutionBackend backend;
        volatile long lastUsedMs;

        BackendEntry(ExecutionBackend backend) {
            this.backend = backend;
            this.lastUsedMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    private record SessionMeta(BackendConfig backendConfig) {}
}
