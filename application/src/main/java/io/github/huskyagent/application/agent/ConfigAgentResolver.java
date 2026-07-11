package io.github.huskyagent.application.agent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.tool.Toolset;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Data
@Component
public class ConfigAgentResolver implements AgentResolver, EnvironmentAware, InitializingBean {

    private Map<String, AgentProperties> agents = new LinkedHashMap<>();

    private final ConcurrentHashMap<String, AgentDefinition> resolved = new ConcurrentHashMap<>();
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment == null) {
            return;
        }
        Map<String, AgentProperties> bound = bindAgents(environment);
        if (!bound.isEmpty()) {
            setAgents(bound);
        }
    }

    public AgentDefinition resolve(String agentId) {
        String effectiveAgentId = agentId != null && !agentId.isBlank() ? agentId : null;
        if (effectiveAgentId == null || effectiveAgentId.isBlank() || !agents.containsKey(effectiveAgentId)) {
            throw new IllegalArgumentException("Unknown agent: " + effectiveAgentId);
        }
        return resolved.computeIfAbsent(effectiveAgentId, this::buildAgentDefinition);
    }

    public AgentDefinition resolveDefault() {
        return resolve("assistant");
    }

    public Set<String> agentIds() {
        return agents != null ? Set.copyOf(agents.keySet()) : Set.of();
    }

    public void setAgents(Map<String, AgentProperties> agents) {
        this.agents = agents != null ? new LinkedHashMap<>(agents) : new LinkedHashMap<>();
        this.resolved.clear();
    }

    public Map<String, AgentProperties> getConfigs() {
        return getAgents();
    }

    public void setConfigs(Map<String, AgentProperties> configs) {
        setAgents(configs);
    }

    private AgentDefinition buildAgentDefinition(String agentId) {
        AgentProperties props = agents.get(agentId);
        AgentDefinition config = new AgentDefinition();
        config.setAgentId(agentId);

        if (props != null) {
            config.setSystemPrompt(props.getSystemPrompt());
            config.setAllowedToolsets(toToolsets(props.getToolsets()));
            config.setAllowedTools(toSet(props.getAllowedTools()));
            config.setDeniedTools(toSet(props.getDeniedTools()));
            config.setAllowedMcpServers(toSet(firstNonNull(props.getAllowedMcpServers(), props.getMcpAllowlist())));
            config.setDeniedMcpServers(toSet(firstNonNull(props.getDeniedMcpServers(), props.getMcpDenylist())));
            config.setKnowledgeSources(toSet(props.getKnowledgeSources()));
            config.setApprovalPolicy(toApprovalPolicy(props.getApproval()));
            config.setBackendPolicy(toBackendPolicy(props.getBackend()));
            config.setWorkingDirectoryPolicy(toWorkingDirPolicy(props.getWorkingDir()));
            config.setFixedWorkingDirectory(props.getFixedWorkingDir());
            config.setMemoryPolicy(toMemoryPolicy(props.getMemoryPolicy()));
            if (props.getMemory() != null) {
                config.setMemoryPolicyConfig(toMemoryPolicySpec(props.getMemory(), config.getMemoryPolicyConfig()));
            }
            if (props.getContext() != null) {
                config.setContextPolicy(toContextPolicySpec(props.getContext(), config.getContextPolicy()));
            }
            config.setPromptFiles(props.getPromptFiles() != null ? props.getPromptFiles() : List.of());
            config.setPromptFilePolicy(toPromptFilePolicy(props.getPromptFilePolicy()));
            config.setAuditSpec(toAuditSpec(props));
            config.setRateLimitSpec(toRateLimitSpec(props));
            config.setDelegationSpec(toDelegationSpec(props.getDelegation()));
            config.setSkillIds(toSet(props.getSkills()));
            config.setBackendSpec(toBackendSpec(props));
            config.setStoragePolicy(toStoragePolicy(props.getStorage()));
            config.setStorageSpec(toStorageSpec(props));
            config.setModelSelection(toModelSelection(props.getModel()));
        }

        ModelSelection model = config.getModelSelection();
        log.info("Resolved agent configuration: agentId={}, model={}, toolsets={}, allowedTools={}, denied={}, approval={}, backend={}, workDir={}",
                agentId,
                model != null ? model.fingerprint() : "default",
                config.getAllowedToolsets(), config.getAllowedTools(), config.getDeniedTools(),
                config.getApprovalPolicy(), config.getBackendPolicy(), config.getWorkingDirectoryPolicy());
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, AgentProperties> bindAgents(Environment environment) {
        ResolvableType type = ResolvableType.forClassWithGenerics(Map.class, String.class, AgentProperties.class);
        return (Map<String, AgentProperties>) Binder.get(environment)
                .bind("agents", Bindable.of(type))
                .orElseGet(LinkedHashMap::new);
    }

    private Set<Toolset> toToolsets(List<String> names) {
        // Empty / missing = no toolsets (fail-closed). Use ["*"] for all.
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        for (String name : names) {
            if (name != null && ("*".equals(name.trim()) || "all".equalsIgnoreCase(name.trim()))) {
                return Set.of(Toolset.values());
            }
        }
        Set<Toolset> result = new HashSet<>();
        for (String name : names) {
            try {
                result.add(Toolset.valueOf(name.toUpperCase().replace("-", "_")));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown Toolset name: {}", name);
            }
        }
        return result;
    }

    private Set<String> toSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        // Normalize "*" / "all" tokens for allowlists
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("*".equals(trimmed) || "all".equalsIgnoreCase(trimmed)) {
                result.add("*");
            } else {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }

    private <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private AgentDefinition.ApprovalPolicy toApprovalPolicy(String value) {
        if (value == null) return AgentDefinition.ApprovalPolicy.REQUIRED;
        return switch (value.toLowerCase()) {
            case "none" -> AgentDefinition.ApprovalPolicy.NONE;
            case "auto-approve-safe" -> AgentDefinition.ApprovalPolicy.AUTO_APPROVE_SAFE;
            default -> AgentDefinition.ApprovalPolicy.REQUIRED;
        };
    }

    private AgentDefinition.BackendPolicy toBackendPolicy(String value) {
        if (value == null) return AgentDefinition.BackendPolicy.LOCAL;
        return switch (value.toLowerCase()) {
            case "docker" -> AgentDefinition.BackendPolicy.DOCKER;
            case "ssh" -> AgentDefinition.BackendPolicy.SSH;
            default -> AgentDefinition.BackendPolicy.LOCAL;
        };
    }

    private AgentDefinition.StoragePolicy toStoragePolicy(String value) {
        if (value == null) return AgentDefinition.StoragePolicy.LOCAL;
        return switch (value.toLowerCase()) {
            case "remote" -> AgentDefinition.StoragePolicy.REMOTE;
            default -> AgentDefinition.StoragePolicy.LOCAL;
        };
    }

    private AgentDefinition.WorkingDirectoryPolicy toWorkingDirPolicy(String value) {
        if (value == null) return AgentDefinition.WorkingDirectoryPolicy.INHERIT;
        return switch (value.toLowerCase()) {
            case "fixed" -> AgentDefinition.WorkingDirectoryPolicy.FIXED;
            default -> AgentDefinition.WorkingDirectoryPolicy.INHERIT;
        };
    }

    private AgentDefinition.LegacyMemoryPolicy toMemoryPolicy(String value) {
        if (value == null) return AgentDefinition.LegacyMemoryPolicy.SESSION;
        return switch (value.toLowerCase()) {
            case "disabled" -> AgentDefinition.LegacyMemoryPolicy.DISABLED;
            case "readonly", "read-only" -> AgentDefinition.LegacyMemoryPolicy.READONLY;
            case "user-profile", "user_profile" -> AgentDefinition.LegacyMemoryPolicy.USER_PROFILE;
            case "principal" -> AgentDefinition.LegacyMemoryPolicy.PRINCIPAL;
            case "scene", "agent" -> AgentDefinition.LegacyMemoryPolicy.AGENT;
            default -> AgentDefinition.LegacyMemoryPolicy.SESSION;
        };
    }

    private AgentDefinition.ContextPolicySpec toContextPolicySpec(ContextProperties props, AgentDefinition.ContextPolicySpec defaults) {
        AgentDefinition.ContextPolicySpec spec = defaults != null ? defaults : new AgentDefinition.ContextPolicySpec();
        if (props.getEnabled() != null) spec.setEnabled(props.getEnabled());
        if (props.getMode() != null) spec.setMode(props.getMode());
        if (props.getStrategy() != null) spec.setStrategy(props.getStrategy());
        if (props.getPruneStrategy() != null) spec.setPruneStrategy(props.getPruneStrategy());
        if (props.getSummaryStrategy() != null) spec.setSummaryStrategy(props.getSummaryStrategy());
        if (props.getThresholdPercent() != null) spec.setThresholdPercent(props.getThresholdPercent());
        if (props.getContextLength() != null) spec.setContextLength(props.getContextLength());
        if (props.getProtectFirstN() != null) spec.setProtectFirstN(props.getProtectFirstN());
        if (props.getTailTokenBudget() != null) spec.setTailTokenBudget(props.getTailTokenBudget());
        if (props.getMaxSummaryTokens() != null) spec.setMaxSummaryTokens(props.getMaxSummaryTokens());
        if (props.getSummaryFocus() != null) spec.setSummaryFocus(props.getSummaryFocus());
        return spec;
    }

    private AgentDefinition.MemoryPolicySpec toMemoryPolicySpec(MemoryProperties props, AgentDefinition.MemoryPolicySpec defaults) {
        AgentDefinition.MemoryPolicySpec spec = defaults != null ? defaults : new AgentDefinition.MemoryPolicySpec();
        if (props.getEnabled() != null) spec.setEnabled(props.getEnabled());
        if (props.getStrategy() != null) spec.setStrategy(props.getStrategy());
        if (props.getAccess() != null) spec.setAccess(toMemoryAccess(props.getAccess()));
        if (props.getScope() != null) spec.setScope(toMemoryScope(props.getScope()));
        if (props.getProviders() != null) spec.setProviders(Set.copyOf(props.getProviders()));
        if (props.getPromptMode() != null) spec.setPromptMode(toMemoryPromptMode(props.getPromptMode()));
        if (props.getAllowCrossSessionSearch() != null) spec.setAllowCrossSessionSearch(props.getAllowCrossSessionSearch());
        return spec;
    }

    private AgentDefinition.MemoryAccess toMemoryAccess(String value) {
        return switch (value.toLowerCase()) {
            case "disabled", "none" -> AgentDefinition.MemoryAccess.DISABLED;
            case "readonly", "read-only" -> AgentDefinition.MemoryAccess.READONLY;
            default -> AgentDefinition.MemoryAccess.READWRITE;
        };
    }

    private AgentDefinition.MemoryScopePolicy toMemoryScope(String value) {
        return switch (value.toLowerCase()) {
            case "principal" -> AgentDefinition.MemoryScopePolicy.PRINCIPAL;
            case "scene", "agent" -> AgentDefinition.MemoryScopePolicy.AGENT;
            case "user-profile", "user_profile" -> AgentDefinition.MemoryScopePolicy.USER_PROFILE;
            default -> AgentDefinition.MemoryScopePolicy.SESSION;
        };
    }

    private AgentDefinition.MemoryPromptMode toMemoryPromptMode(String value) {
        return switch (value.toLowerCase()) {
            case "none" -> AgentDefinition.MemoryPromptMode.NONE;
            case "full" -> AgentDefinition.MemoryPromptMode.FULL;
            case "profile-only", "profile_only" -> AgentDefinition.MemoryPromptMode.PROFILE_ONLY;
            default -> AgentDefinition.MemoryPromptMode.SUMMARY;
        };
    }

    private AgentDefinition.PromptFilePolicy toPromptFilePolicy(String value) {
        if (value == null) return AgentDefinition.PromptFilePolicy.APPEND;
        return switch (value.toLowerCase()) {
            case "override" -> AgentDefinition.PromptFilePolicy.OVERRIDE;
            default -> AgentDefinition.PromptFilePolicy.APPEND;
        };
    }

    private AgentDefinition.AuditSpec toAuditSpec(AgentProperties props) {
        AgentDefinition.AuditSpec spec = new AgentDefinition.AuditSpec();
        if (props.getAuditEnabled() != null) spec.setEnabled(props.getAuditEnabled());
        spec.setTags(toSet(props.getAuditTags()));
        return spec;
    }

    private AgentDefinition.RateLimitSpec toRateLimitSpec(AgentProperties props) {
        AgentDefinition.RateLimitSpec spec = new AgentDefinition.RateLimitSpec();
        if (props.getRateLimitEnabled() != null) spec.setEnabled(props.getRateLimitEnabled());
        spec.setRequestsPerMinute(props.getRateLimitRequestsPerMinute());
        spec.setBurst(props.getRateLimitBurst());
        validateRateLimitSpec(spec);
        return spec;
    }

    private AgentDefinition.DelegationSpec toDelegationSpec(DelegationProperties props) {
        AgentDefinition.DelegationSpec spec = new AgentDefinition.DelegationSpec();
        if (props == null) {
            return spec;
        }
        spec.setEnabled(props.getEnabled());
        spec.setMaxIterations(props.getMaxIterations());
        spec.setMaxConcurrentChildren(props.getMaxConcurrentChildren());
        spec.setMaxSpawnDepth(props.getMaxSpawnDepth());
        spec.setChildTimeoutSeconds(props.getChildTimeoutSeconds());
        if (props.getBlockedToolsets() != null) {
            spec.setBlockedToolsets(List.copyOf(props.getBlockedToolsets()));
        }
        if (props.getDefaultToolsets() != null) {
            spec.setDefaultToolsets(List.copyOf(props.getDefaultToolsets()));
        }
        spec.setModel(props.getModel());
        return spec;
    }

    private void validateRateLimitSpec(AgentDefinition.RateLimitSpec spec) {
        if (spec == null || !spec.isEnabled()) {
            return;
        }
        Integer rpm = spec.getRequestsPerMinute();
        if (rpm == null || rpm <= 0) {
            throw new IllegalArgumentException(
                    "rate-limit-requests-per-minute must be > 0 when rate-limit-enabled is true");
        }
        if (spec.getBurst() != null && spec.getBurst() < 1) {
            throw new IllegalArgumentException("rate-limit-burst must be >= 1 when set");
        }
    }

    private AgentDefinition.BackendSpec toBackendSpec(AgentProperties props) {
        if (props.getDockerImage() == null && props.getDockerMemory() == null
                && props.getDockerCpus() == null && props.getDockerWorkdir() == null
                && props.getDockerPersistFilesystem() == null && props.getSshHost() == null) {
            return null;
        }
        AgentDefinition.BackendSpec spec = new AgentDefinition.BackendSpec();
        spec.setDockerImage(props.getDockerImage());
        spec.setDockerMemory(props.getDockerMemory());
        spec.setDockerCpus(props.getDockerCpus());
        spec.setDockerPersistFilesystem(props.getDockerPersistFilesystem());
        spec.setDockerWorkdir(props.getDockerWorkdir());
        spec.setSshHost(props.getSshHost());
        if (props.getSshPort() != null) spec.setSshPort(props.getSshPort());
        spec.setSshUser(props.getSshUser());
        spec.setSshIdentityFile(props.getSshIdentityFile());
        return spec;
    }

    /**
     * Accepts either a bare model name string or a map:
     * <pre>
     * model: gpt-5.4
     * model:
     *   provider: deepseek
     *   name: deepseek-chat
     *   temperature: 0.3
     *   max-tokens: 8192
     * </pre>
     */
    @SuppressWarnings("unchecked")
    ModelSelection toModelSelection(Object model) {
        if (model == null) {
            return null;
        }
        if (model instanceof String name) {
            if (name.isBlank()) {
                return null;
            }
            return ModelSelection.builder().modelName(name.trim()).build();
        }
        if (model instanceof Map<?, ?> map) {
            String provider = stringValue(map.get("provider"));
            String name = firstNonBlank(stringValue(map.get("name")), stringValue(map.get("model")));
            Double temperature = doubleValue(map.get("temperature"));
            Integer maxTokens = intValue(firstNonNull(map.get("max-tokens"), map.get("maxTokens")));
            if (isBlank(provider) && isBlank(name) && temperature == null && maxTokens == null) {
                return null;
            }
            return ModelSelection.builder()
                    .providerId(isBlank(provider) ? null : provider)
                    .modelName(isBlank(name) ? null : name)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
        }
        log.warn("Ignoring unsupported agents.*.model type: {}", model.getClass().getName());
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString().trim() : null;
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AgentDefinition.StorageSpec toStorageSpec(AgentProperties props) {
        if (props.getStorageSpec() == null) {
            return null;
        }
        StorageProperties source = props.getStorageSpec();
        if (source.getWorkspaceType() == null && source.getWorkspaceEndpoint() == null
                && source.getWorkspaceBucket() == null && source.getWorkspaceRegion() == null
                && source.getWorkspacePrefix() == null && source.getCheckpointType() == null
                && source.getCheckpointUrl() == null && source.getCheckpointTable() == null) {
            return null;
        }
        AgentDefinition.StorageSpec spec = new AgentDefinition.StorageSpec();
        spec.setWorkspaceType(source.getWorkspaceType());
        spec.setWorkspaceEndpoint(source.getWorkspaceEndpoint());
        spec.setWorkspaceBucket(source.getWorkspaceBucket());
        spec.setWorkspaceRegion(source.getWorkspaceRegion());
        spec.setWorkspacePrefix(source.getWorkspacePrefix());
        spec.setCheckpointType(source.getCheckpointType());
        spec.setCheckpointUrl(source.getCheckpointUrl());
        spec.setCheckpointTable(source.getCheckpointTable());
        return spec;
    }

    @Data
    public static class AgentProperties {
        private String systemPrompt;
        /**
         * Either a model name string or a map with provider/name/temperature/max-tokens.
         */
        private Object model;
        private List<String> toolsets;
        private Set<String> allowedTools;
        private Set<String> deniedTools;
        private Set<String> allowedMcpServers;
        private Set<String> deniedMcpServers;
        private Set<String> knowledgeSources;
        private Set<String> mcpAllowlist;
        private Set<String> mcpDenylist;
        private String approval;
        private String backend;
        private String storage;
        private String workingDir;
        private String fixedWorkingDir;
        private String memoryPolicy;
        private ContextProperties context;
        private MemoryProperties memory;
        private StorageProperties storageSpec;
        private List<String> promptFiles;
        private String promptFilePolicy;
        private Boolean auditEnabled;
        private Set<String> auditTags;
        private Boolean rateLimitEnabled;
        private Integer rateLimitRequestsPerMinute;
        private Integer rateLimitBurst;
        /**
         * Optional anonymous subagent overrides for this agent ({@code agents.*.delegation}).
         */
        private DelegationProperties delegation;
        private Set<String> skills;
        private String dockerImage;
        private String dockerMemory;
        private String dockerCpus;
        private Boolean dockerPersistFilesystem;
        private String dockerWorkdir;
        private String sshHost;
        private Integer sshPort;
        private String sshUser;
        private String sshIdentityFile;
    }

    @Data
    public static class StorageProperties {
        private String workspaceType;
        private String workspaceEndpoint;
        private String workspaceBucket;
        private String workspaceRegion;
        private String workspacePrefix;
        private String checkpointType;
        private String checkpointUrl;
        private String checkpointTable;
    }

    @Data
    public static class ContextProperties {
        private Boolean enabled;
        private String mode;
        private String strategy;
        private String pruneStrategy;
        private String summaryStrategy;
        private Double thresholdPercent;
        private Integer contextLength;
        private Integer protectFirstN;
        private Integer tailTokenBudget;
        private Integer maxSummaryTokens;
        private String summaryFocus;
    }

    @Data
    public static class MemoryProperties {
        private Boolean enabled;
        private String strategy;
        private String access;
        private String scope;
        private Set<String> providers;
        private String promptMode;
        private Boolean allowCrossSessionSearch;
    }

    @Data
    public static class DelegationProperties {
        private Boolean enabled;
        private Integer maxIterations;
        private Integer maxConcurrentChildren;
        private Integer maxSpawnDepth;
        private Long childTimeoutSeconds;
        private List<String> blockedToolsets;
        private List<String> defaultToolsets;
        private String model;
    }
}
