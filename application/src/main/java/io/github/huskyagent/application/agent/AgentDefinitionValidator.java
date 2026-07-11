package io.github.huskyagent.application.agent;

import io.github.huskyagent.application.runtime.AllowlistSemantics;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.llm.LlmClientRegistry;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.mcp.McpConfigLoader;
import io.github.huskyagent.infra.mcp.McpToolNames;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed validation of a resolved {@link AgentDefinition} against platform catalogs
 * (skills, knowledge sources, memory providers, MCP servers, LLM providers) and structural rules.
 */
@Slf4j
@Component
public class AgentDefinitionValidator {

    private final SkillManager skillManager;
    private final KnowledgeManager knowledgeManager;
    private final MemoryManager memoryManager;
    private final ObjectProvider<McpConfigLoader> mcpConfigLoaderProvider;
    private final ObjectProvider<LlmClientRegistry> llmClientRegistryProvider;

    public AgentDefinitionValidator(SkillManager skillManager,
                                    KnowledgeManager knowledgeManager,
                                    MemoryManager memoryManager,
                                    ObjectProvider<McpConfigLoader> mcpConfigLoaderProvider,
                                    ObjectProvider<LlmClientRegistry> llmClientRegistryProvider) {
        this.skillManager = skillManager;
        this.knowledgeManager = knowledgeManager;
        this.memoryManager = memoryManager;
        this.mcpConfigLoaderProvider = mcpConfigLoaderProvider;
        this.llmClientRegistryProvider = llmClientRegistryProvider;
    }

    /**
     * Validates the agent definition. Throws {@link IllegalArgumentException} on the first batch of errors.
     */
    public void validate(AgentDefinition definition) {
        Objects.requireNonNull(definition, "agent definition is required");
        String agentId = definition.getAgentId() != null ? definition.getAgentId() : "<unknown>";
        List<String> errors = new ArrayList<>();

        validateRateLimit(definition.getRateLimitSpec(), errors);
        validateSkillIds(definition.getSkillIds(), errors);
        validateKnowledgeSources(definition.getKnowledgeSources(), errors);
        validateMemoryProviders(definition.getMemoryPolicyConfig(), errors);
        validateMcpServers(definition.getAllowedMcpServers(), definition.getDeniedMcpServers(), errors);
        validateModelSelection(definition.getModelSelection(), errors);
        validateWorkingDirectory(definition, errors);
        validateDelegationToolsets(definition.getDelegationSpec(), errors);

        if (!errors.isEmpty()) {
            String message = "Invalid agent '" + agentId + "': " + String.join("; ", errors);
            throw new IllegalArgumentException(message);
        }
    }

    private void validateRateLimit(AgentDefinition.RateLimitSpec spec, List<String> errors) {
        if (spec == null || !spec.isEnabled()) {
            return;
        }
        Integer rpm = spec.getRequestsPerMinute();
        if (rpm == null || rpm <= 0) {
            errors.add("rate-limit-requests-per-minute must be > 0 when rate-limit-enabled is true");
        }
        if (spec.getBurst() != null && spec.getBurst() < 1) {
            errors.add("rate-limit-burst must be >= 1 when set");
        }
    }

    private void validateSkillIds(Set<String> skillIds, List<String> errors) {
        Set<String> concrete = AllowlistSemantics.concreteIds(skillIds);
        if (concrete.isEmpty()) {
            return;
        }
        Set<String> known = skillManager.getAllSkills().stream()
                .map(Skill::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String skillId : concrete) {
            if (!known.contains(skillId)) {
                errors.add("unknown skill '" + skillId + "'");
            }
        }
    }

    private void validateKnowledgeSources(Set<String> knowledgeSources, List<String> errors) {
        Set<String> concrete = AllowlistSemantics.concreteIds(knowledgeSources);
        if (concrete.isEmpty()) {
            // empty or only "*" / "all" → unrestricted or none; both valid
            return;
        }
        try {
            // Always validate concrete ids even when "*" is also present (catch typos).
            knowledgeManager.validateSourceIds(concrete);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private void validateMemoryProviders(AgentDefinition.MemoryPolicySpec memory, List<String> errors) {
        if (memory == null || memory.getProviders() == null || memory.getProviders().isEmpty()) {
            // Empty = all registered providers at runtime (not an allowlist "*").
            return;
        }
        // Memory providers do NOT support "*" / "all" — empty means all.
        for (String providerId : memory.getProviders()) {
            if (providerId != null && AllowlistSemantics.isAllToken(providerId)) {
                errors.add("memory.providers does not support '" + providerId.trim()
                        + "'; omit providers (or use []) for all registered memory providers");
            }
        }
        Set<String> concrete = AllowlistSemantics.concreteIds(memory.getProviders());
        if (concrete.isEmpty()) {
            return;
        }
        try {
            memoryManager.validateProviderIds(concrete);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private void validateMcpServers(Set<String> allowed, Set<String> denied, List<String> errors) {
        Set<String> concrete = new LinkedHashSet<>();
        concrete.addAll(AllowlistSemantics.concreteIds(allowed));
        concrete.addAll(AllowlistSemantics.concreteIds(denied));
        if (concrete.isEmpty()) {
            return;
        }

        McpConfigLoader loader = mcpConfigLoaderProvider != null ? mcpConfigLoaderProvider.getIfAvailable() : null;
        if (loader == null) {
            errors.add("MCP servers referenced (" + String.join(", ", concrete)
                    + ") but MCP is not enabled (mcp.enabled=false) or config loader is unavailable");
            return;
        }

        McpConfigLoader.ConfigLoadResult load = loader.loadAllServersResult();
        if (!load.success()) {
            errors.add("MCP config could not be loaded while validating server references: " + load.errorMessage());
            return;
        }
        Set<String> configured = load.servers() != null ? load.servers().keySet() : Set.of();
        Set<String> configuredNormalized = configured.stream()
                .flatMap(name -> java.util.stream.Stream.of(name, McpToolNames.sanitize(name)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String server : concrete) {
            if (!configured.contains(server) && !configuredNormalized.contains(McpToolNames.sanitize(server))) {
                errors.add("unknown MCP server '" + server + "'");
            }
        }
    }

    private void validateModelSelection(ModelSelection modelSelection, List<String> errors) {
        if (modelSelection == null) {
            return;
        }
        String providerId = modelSelection.getProviderId();
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        LlmClientRegistry registry = llmClientRegistryProvider != null
                ? llmClientRegistryProvider.getIfAvailable()
                : null;
        if (registry == null) {
            errors.add("LLM provider '" + providerId.trim()
                    + "' referenced but LlmClientRegistry is unavailable");
            return;
        }
        try {
            registry.requireProvider(providerId.trim());
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
    }

    private void validateWorkingDirectory(AgentDefinition definition, List<String> errors) {
        if (definition.getWorkingDirectoryPolicy() != AgentDefinition.WorkingDirectoryPolicy.FIXED) {
            return;
        }
        String fixed = definition.getFixedWorkingDirectory();
        if (fixed == null || fixed.isBlank()) {
            errors.add("working-dir=fixed requires fixed-working-dir");
        }
    }

    private void validateDelegationToolsets(AgentDefinition.DelegationSpec delegation, List<String> errors) {
        if (delegation == null) {
            return;
        }
        errors.addAll(unknownToolsetNames(delegation.getBlockedToolsets(), "delegation.blocked-toolsets"));
        errors.addAll(unknownToolsetNames(delegation.getDefaultToolsets(), "delegation.default-toolsets"));
    }

    private static List<String> unknownToolsetNames(List<String> names, String field) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim();
            // Runtime EffectiveDelegationPolicy has no "*" support; empty default-toolsets = all except blocked.
            if (AllowlistSemantics.isAllToken(normalized)) {
                errors.add(field + " does not support '" + normalized
                        + "'; use an explicit toolset list, or omit/empty default-toolsets for all except blocked");
                continue;
            }
            try {
                Toolset.valueOf(normalized.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                errors.add("unknown toolset '" + name + "' in " + field);
            }
        }
        return errors;
    }

}
