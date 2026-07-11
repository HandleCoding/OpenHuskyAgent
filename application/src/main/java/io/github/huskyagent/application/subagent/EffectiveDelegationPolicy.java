package io.github.huskyagent.application.subagent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.tool.Toolset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Merged anonymous-subagent policy: global {@code agent.delegation} + optional
 * {@code agents.*.delegation} + tool-call parameters (applied by callers).
 *
 * <p>Merge rules:
 * <ul>
 *   <li>enabled: global AND agent (agent cannot re-enable when global is off)</li>
 *   <li>numeric ceilings: minimum of configured layers (stricter wins)</li>
 *   <li>blocked toolsets: union</li>
 *   <li>default toolsets: agent overrides global when non-empty; empty = all except blocked</li>
 *   <li>model: agent string, else global string, else inherit parent (handled by caller)</li>
 * </ul>
 */
public record EffectiveDelegationPolicy(
        boolean enabled,
        int maxIterations,
        int maxConcurrentChildren,
        int maxSpawnDepth,
        long childTimeoutSeconds,
        Set<String> blockedToolsets,
        List<String> defaultToolsets,
        String model
) {

    public static EffectiveDelegationPolicy merge(SubAgentConfig global, AgentDefinition.DelegationSpec agent) {
        SubAgentConfig g = global != null ? global : new SubAgentConfig();
        AgentDefinition.DelegationSpec a = agent;

        boolean enabled = g.isEnabled();
        if (a != null && a.getEnabled() != null) {
            enabled = enabled && a.getEnabled();
        }

        int maxIterations = minPositive(g.getMaxIterations(), a != null ? a.getMaxIterations() : null, 50);
        int maxConcurrent = minPositive(g.getMaxConcurrentChildren(), a != null ? a.getMaxConcurrentChildren() : null, 3);
        int maxSpawnDepth = minPositive(g.getMaxSpawnDepth(), a != null ? a.getMaxSpawnDepth() : null, 1);
        long timeout = minPositiveLong(g.getChildTimeoutSeconds(), a != null ? a.getChildTimeoutSeconds() : null, 600L);

        Set<String> blocked = new LinkedHashSet<>();
        blocked.addAll(normalizeNames(g.getBlockedToolsets()));
        if (a != null) {
            blocked.addAll(normalizeNames(a.getBlockedToolsets()));
        }

        List<String> defaults = nonEmptyList(a != null ? a.getDefaultToolsets() : null);
        if (defaults == null) {
            defaults = nonEmptyList(g.getDefaultToolsets());
        }
        if (defaults == null) {
            defaults = List.of();
        }

        String model = firstNonBlank(a != null ? a.getModel() : null, g.getModel());

        return new EffectiveDelegationPolicy(
                enabled,
                maxIterations,
                maxConcurrent,
                maxSpawnDepth,
                timeout,
                Set.copyOf(blocked),
                List.copyOf(defaults),
                model);
    }

    public int resolveMaxSteps(Integer toolMaxSteps) {
        if (toolMaxSteps == null || toolMaxSteps <= 0) {
            return maxIterations;
        }
        return Math.min(toolMaxSteps, maxIterations);
    }

    public long resolveTimeoutSeconds(Long toolTimeoutSeconds) {
        if (toolTimeoutSeconds == null || toolTimeoutSeconds <= 0) {
            return childTimeoutSeconds;
        }
        return Math.min(toolTimeoutSeconds, childTimeoutSeconds);
    }

    public int resolveMaxConcurrent(int taskCount) {
        return Math.min(Math.max(1, maxConcurrentChildren), Math.max(1, taskCount));
    }

    /**
     * Resolve child toolsets: requested (if any) else defaultToolsets else all, minus blocked.
     */
    public Set<Toolset> resolveAllowedToolsets(List<String> requested) {
        Set<Toolset> blocked = toToolsets(blockedToolsets);

        List<String> base = (requested != null && !requested.isEmpty()) ? requested : defaultToolsets;
        if (base == null || base.isEmpty()) {
            return Arrays.stream(Toolset.values())
                    .filter(ts -> !blocked.contains(ts))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        return toToolsets(base).stream()
                .filter(ts -> !blocked.contains(ts))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Explicit model override for the child, or null when the child should inherit parent / platform default.
     */
    public ModelSelection modelOverride() {
        if (model == null || model.isBlank()) {
            return null;
        }
        return ModelSelection.builder().modelName(model.trim()).build();
    }

    private static int minPositive(int global, Integer agent, int fallback) {
        int base = global > 0 ? global : fallback;
        if (agent == null || agent <= 0) {
            return base;
        }
        return Math.min(base, agent);
    }

    private static long minPositiveLong(long global, Long agent, long fallback) {
        long base = global > 0 ? global : fallback;
        if (agent == null || agent <= 0) {
            return base;
        }
        return Math.min(base, agent);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    private static List<String> nonEmptyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = normalizeNames(values);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static List<String> normalizeNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        return result;
    }

    private static Set<Toolset> toToolsets(Iterable<String> names) {
        if (names == null) {
            return Set.of();
        }
        Set<Toolset> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                result.add(Toolset.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException ignored) {
                // skip unknown
            }
        }
        return result;
    }
}
