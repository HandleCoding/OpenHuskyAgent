package io.github.huskyagent.infra.ai;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Component
public class DynamicPromptSnapshotCache {

    private static final String NO_SESSION = "<no-session>";
    private static final String NO_TURN = "<no-turn>";

    private final ConcurrentMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();

    public Snapshot getOrCreate(String sessionId, String turnId, String userTurnKey, Supplier<String> promptSupplier) {
        if (userTurnKey == null) {
            return Snapshot.miss(promptSupplier.get());
        }

        Key key = new Key(normalize(sessionId, NO_SESSION), normalize(turnId, NO_TURN), userTurnKey);
        Snapshot cached = snapshots.get(key);
        if (cached != null) {
            return cached.asHit();
        }

        Snapshot snapshot = Snapshot.miss(promptSupplier.get());
        Snapshot existing = snapshots.putIfAbsent(key, snapshot);
        return existing != null ? existing.asHit() : snapshot;
    }

    public void clearTurn(String sessionId, String turnId) {
        String normalizedSessionId = normalize(sessionId, NO_SESSION);
        String normalizedTurnId = normalize(turnId, NO_TURN);
        snapshots.keySet().removeIf(key -> key.sessionId().equals(normalizedSessionId)
                && key.turnId().equals(normalizedTurnId));
    }

    private static String normalize(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private record Key(String sessionId, String turnId, String userTurnKey) {
    }

    public record Snapshot(String prompt, String promptHash, boolean cacheHit) {
        private static Snapshot miss(String prompt) {
            String value = Objects.requireNonNullElse(prompt, "");
            return new Snapshot(value, Integer.toHexString(value.hashCode()), false);
        }

        private Snapshot asHit() {
            return new Snapshot(prompt, promptHash, true);
        }
    }
}
