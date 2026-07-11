package io.github.huskyagent.infra.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class KnowledgeManager {
    private final List<KnowledgeProvider> providers;
    private final KnowledgeConfig config;

    public KnowledgeManager(List<KnowledgeProvider> providers, KnowledgeConfig config) {
        this.providers = List.copyOf(providers);
        this.config = config;
        log.info("Registered {} knowledge providers: {}", providers.size(),
                providers.stream().map(KnowledgeProvider::getName).toList());
    }

    public List<KnowledgeProvider> getProviders() {
        return providers;
    }

    /**
     * Collect all valid source ids across providers (provider names + per-provider source ids).
     */
    public Set<String> allSourceIds() {
        Set<String> result = new java.util.HashSet<>();
        for (KnowledgeProvider provider : providers) {
            result.add(provider.getName());
            result.addAll(provider.getSourceIds());
        }
        return result;
    }

    public void validateSourceIds(Set<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        if (containsAllToken(sourceIds)) {
            return;
        }
        Set<String> registered = allSourceIds();
        for (String sourceId : sourceIds) {
            if (isAllToken(sourceId)) {
                continue;
            }
            if (!registered.contains(sourceId)) {
                throw new IllegalArgumentException("Unknown knowledge source: " + sourceId);
            }
        }
    }

    public List<KnowledgeResult> search(KnowledgeQuery query, Set<String> allowedSourceIds) {
        if (!config.isEnabled()) {
            return List.of();
        }
        int topK = clampTopK(query.topK());
        Set<String> effectiveAllowed = resolveAllowedSources(allowedSourceIds);
        if (effectiveAllowed.isEmpty()) {
            return List.of();
        }

        return providers.stream()
                .filter(KnowledgeProvider::isAvailable)
                .filter(provider -> providerSupportsAnyAllowed(provider, effectiveAllowed))
                .map(provider -> provider.search(
                        new KnowledgeQuery(query.query(), topK, query.source(), query.metadata()),
                        effectiveAllowed))
                .flatMap(Collection::stream)
                .sorted(Comparator.comparingDouble(KnowledgeResult::score).reversed())
                .limit(topK)
                .toList();
    }

    public Optional<KnowledgeDocument> fetch(String id, Set<String> allowedSourceIds) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        Set<String> effectiveAllowed = resolveAllowedSources(allowedSourceIds);
        if (effectiveAllowed.isEmpty()) {
            return Optional.empty();
        }
        String providerName = parseProviderName(id);

        return providers.stream()
                .filter(KnowledgeProvider::isAvailable)
                .filter(provider -> providerName == null || provider.getName().equals(providerName))
                .filter(provider -> providerSupportsAnyAllowed(provider, effectiveAllowed))
                .map(provider -> provider.fetch(id, effectiveAllowed))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public String describeSources(Set<String> allowedSourceIds) {
        if (!config.isEnabled()) {
            return "";
        }
        Set<String> effectiveAllowed = resolveAllowedSources(allowedSourceIds);
        if (effectiveAllowed.isEmpty()) {
            return "";
        }

        List<KnowledgeProvider> active = providers.stream()
                .filter(KnowledgeProvider::isAvailable)
                .filter(provider -> providerSupportsAnyAllowed(provider, effectiveAllowed))
                .toList();
        if (active.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeProvider provider : active) {
            sb.append("- ").append(provider.getName()).append(": ").append(provider.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private boolean providerSupportsAnyAllowed(KnowledgeProvider provider, Set<String> allowed) {
        return allowed.contains(provider.getName()) || allowed.stream().anyMatch(provider::supportsSource);
    }

    /**
     * Empty allowlist = no sources. {@code *} / {@code all} = every registered source.
     */
    private Set<String> resolveAllowedSources(Set<String> allowedSourceIds) {
        if (allowedSourceIds == null || allowedSourceIds.isEmpty()) {
            return Set.of();
        }
        if (containsAllToken(allowedSourceIds)) {
            return allSourceIds();
        }
        return allowedSourceIds;
    }

    private static boolean containsAllToken(Set<String> sourceIds) {
        return sourceIds.stream().anyMatch(KnowledgeManager::isAllToken);
    }

    private static boolean isAllToken(String value) {
        if (value == null) {
            return false;
        }
        String t = value.trim();
        return "*".equals(t) || "all".equalsIgnoreCase(t);
    }

    private int clampTopK(int topK) {
        int fallback = config.getDefaultTopK() > 0 ? config.getDefaultTopK() : 5;
        int requested = topK > 0 ? topK : fallback;
        int max = config.getMaxTopK() > 0 ? config.getMaxTopK() : 20;
        return Math.min(requested, max);
    }

    private String parseProviderName(String id) {
        if (id == null) {
            return null;
        }
        int idx = id.indexOf(':');
        return idx > 0 ? id.substring(0, idx) : null;
    }
}