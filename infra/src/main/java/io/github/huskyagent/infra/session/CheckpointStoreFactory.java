package io.github.huskyagent.infra.session;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CheckpointStoreFactory {

    private final Map<String, CheckpointStoreProvider> providers;

    public CheckpointStoreFactory(List<CheckpointStoreProvider> providers) {
        this.providers = buildProviderMap(providers);
    }

    public CheckpointStore forCheckpointType(String checkpointType) {
        String type = normalizeType(checkpointType);
        CheckpointStoreProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported checkpoint store type: " + type);
        }
        return provider.store(null);
    }

    public CheckpointStore forSessionScope(SessionScope scope) {
        String type = scope != null ? scope.getCheckpointType() : null;
        CheckpointStoreProvider provider = providers.get(normalizeType(type));
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported checkpoint store type: " + normalizeType(type));
        }
        return provider.store(scope);
    }

    private Map<String, CheckpointStoreProvider> buildProviderMap(List<CheckpointStoreProvider> providers) {
        Map<String, CheckpointStoreProvider> result = new HashMap<>();
        for (CheckpointStoreProvider provider : providers) {
            String type = normalizeRequiredType(provider.type());
            if (result.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("Duplicate checkpoint store provider type: " + type);
            }
        }
        return Map.copyOf(result);
    }

    private String normalizeRequiredType(String type) {
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Checkpoint store provider type is required");
        }
        return normalized;
    }

    private String normalizeType(String type) {
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isEmpty() ? "local" : normalized;
    }
}
