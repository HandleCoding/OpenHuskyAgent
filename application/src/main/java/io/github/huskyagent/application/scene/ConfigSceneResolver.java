package io.github.huskyagent.application.scene;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.tool.Toolset;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "scenes")
public class ConfigSceneResolver implements SceneResolver {

    private String defaultScene = "assistant";

    private Map<String, SceneProperties> configs = new LinkedHashMap<>();

    private final ConcurrentHashMap<String, SceneConfig> resolved = new ConcurrentHashMap<>();

    public SceneConfig resolve(String sceneId) {
        String effectiveSceneId = sceneId != null && !sceneId.isBlank() ? sceneId : defaultScene;
        if (effectiveSceneId == null || effectiveSceneId.isBlank() || !configs.containsKey(effectiveSceneId)) {
            throw new IllegalArgumentException("Unknown scene: " + effectiveSceneId);
        }
        return resolved.computeIfAbsent(effectiveSceneId, this::buildSceneConfig);
    }

    public SceneConfig resolveDefault() {
        return resolve(defaultScene);
    }

    private SceneConfig buildSceneConfig(String sceneId) {
        SceneProperties props = configs.get(sceneId);
        SceneConfig config = new SceneConfig();
        config.setSceneId(sceneId);

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
            config.setSkillIds(toSet(props.getSkills()));
            config.setBackendSpec(toBackendSpec(props));
            config.setStoragePolicy(toStoragePolicy(props.getStorage()));
            config.setStorageSpec(toStorageSpec(props));
        } else if ("assistant".equals(sceneId)) {
            config.setSystemPrompt(null);
            config.setAllowedToolsets(Set.of(Toolset.values()));
            config.setAllowedTools(Set.of());
            config.setDeniedTools(Set.of());
            config.setAllowedMcpServers(Set.of());
            config.setDeniedMcpServers(Set.of());
            config.setKnowledgeSources(Set.of());
            config.setApprovalPolicy(SceneConfig.ApprovalPolicy.REQUIRED);
            config.setBackendPolicy(SceneConfig.BackendPolicy.LOCAL);
            config.setWorkingDirectoryPolicy(SceneConfig.WorkingDirectoryPolicy.INHERIT);
            config.setMemoryPolicy(SceneConfig.LegacyMemoryPolicy.SESSION);
        }

        log.info("Resolved scene configuration: sceneId={}, toolsets={}, allowedTools={}, denied={}, approval={}, backend={}, workDir={}",
                sceneId, config.getAllowedToolsets(), config.getAllowedTools(), config.getDeniedTools(),
                config.getApprovalPolicy(), config.getBackendPolicy(), config.getWorkingDirectoryPolicy());
        return config;
    }

    private Set<Toolset> toToolsets(List<String> names) {
        if (names == null) return Set.of(Toolset.values());
        Set<Toolset> result = new HashSet<>();
        for (String name : names) {
            try {
                result.add(Toolset.valueOf(name.toUpperCase().replace("-", "_")));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown Toolset name: {}", name);
            }
        }
        return result.isEmpty() ? Set.of(Toolset.values()) : result;
    }

    private Set<String> toSet(Collection<String> values) {
        return values != null ? Set.copyOf(values) : Set.of();
    }

    private <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private SceneConfig.ApprovalPolicy toApprovalPolicy(String value) {
        if (value == null) return SceneConfig.ApprovalPolicy.REQUIRED;
        return switch (value.toLowerCase()) {
            case "none" -> SceneConfig.ApprovalPolicy.NONE;
            case "auto-approve-safe" -> SceneConfig.ApprovalPolicy.AUTO_APPROVE_SAFE;
            default -> SceneConfig.ApprovalPolicy.REQUIRED;
        };
    }

    private SceneConfig.BackendPolicy toBackendPolicy(String value) {
        if (value == null) return SceneConfig.BackendPolicy.LOCAL;
        return switch (value.toLowerCase()) {
            case "docker" -> SceneConfig.BackendPolicy.DOCKER;
            case "ssh" -> SceneConfig.BackendPolicy.SSH;
            default -> SceneConfig.BackendPolicy.LOCAL;
        };
    }

    private SceneConfig.StoragePolicy toStoragePolicy(String value) {
        if (value == null) return SceneConfig.StoragePolicy.LOCAL;
        return switch (value.toLowerCase()) {
            case "remote" -> SceneConfig.StoragePolicy.REMOTE;
            default -> SceneConfig.StoragePolicy.LOCAL;
        };
    }

    private SceneConfig.WorkingDirectoryPolicy toWorkingDirPolicy(String value) {
        if (value == null) return SceneConfig.WorkingDirectoryPolicy.INHERIT;
        return switch (value.toLowerCase()) {
            case "fixed" -> SceneConfig.WorkingDirectoryPolicy.FIXED;
            default -> SceneConfig.WorkingDirectoryPolicy.INHERIT;
        };
    }

    private SceneConfig.LegacyMemoryPolicy toMemoryPolicy(String value) {
        if (value == null) return SceneConfig.LegacyMemoryPolicy.SESSION;
        return switch (value.toLowerCase()) {
            case "disabled" -> SceneConfig.LegacyMemoryPolicy.DISABLED;
            case "readonly", "read-only" -> SceneConfig.LegacyMemoryPolicy.READONLY;
            case "user-profile", "user_profile" -> SceneConfig.LegacyMemoryPolicy.USER_PROFILE;
            case "principal" -> SceneConfig.LegacyMemoryPolicy.PRINCIPAL;
            case "scene" -> SceneConfig.LegacyMemoryPolicy.SCENE;
            default -> SceneConfig.LegacyMemoryPolicy.SESSION;
        };
    }

    private SceneConfig.ContextPolicySpec toContextPolicySpec(ContextProperties props, SceneConfig.ContextPolicySpec defaults) {
        SceneConfig.ContextPolicySpec spec = defaults != null ? defaults : new SceneConfig.ContextPolicySpec();
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

    private SceneConfig.MemoryPolicySpec toMemoryPolicySpec(MemoryProperties props, SceneConfig.MemoryPolicySpec defaults) {
        SceneConfig.MemoryPolicySpec spec = defaults != null ? defaults : new SceneConfig.MemoryPolicySpec();
        if (props.getEnabled() != null) spec.setEnabled(props.getEnabled());
        if (props.getStrategy() != null) spec.setStrategy(props.getStrategy());
        if (props.getAccess() != null) spec.setAccess(toMemoryAccess(props.getAccess()));
        if (props.getScope() != null) spec.setScope(toMemoryScope(props.getScope()));
        if (props.getProviders() != null) spec.setProviders(Set.copyOf(props.getProviders()));
        if (props.getPromptMode() != null) spec.setPromptMode(toMemoryPromptMode(props.getPromptMode()));
        if (props.getAllowCrossSessionSearch() != null) spec.setAllowCrossSessionSearch(props.getAllowCrossSessionSearch());
        return spec;
    }

    private SceneConfig.MemoryAccess toMemoryAccess(String value) {
        return switch (value.toLowerCase()) {
            case "disabled", "none" -> SceneConfig.MemoryAccess.DISABLED;
            case "readonly", "read-only" -> SceneConfig.MemoryAccess.READONLY;
            default -> SceneConfig.MemoryAccess.READWRITE;
        };
    }

    private SceneConfig.MemoryScopePolicy toMemoryScope(String value) {
        return switch (value.toLowerCase()) {
            case "principal" -> SceneConfig.MemoryScopePolicy.PRINCIPAL;
            case "scene" -> SceneConfig.MemoryScopePolicy.SCENE;
            case "user-profile", "user_profile" -> SceneConfig.MemoryScopePolicy.USER_PROFILE;
            default -> SceneConfig.MemoryScopePolicy.SESSION;
        };
    }

    private SceneConfig.MemoryPromptMode toMemoryPromptMode(String value) {
        return switch (value.toLowerCase()) {
            case "none" -> SceneConfig.MemoryPromptMode.NONE;
            case "full" -> SceneConfig.MemoryPromptMode.FULL;
            case "profile-only", "profile_only" -> SceneConfig.MemoryPromptMode.PROFILE_ONLY;
            default -> SceneConfig.MemoryPromptMode.SUMMARY;
        };
    }

    private SceneConfig.PromptFilePolicy toPromptFilePolicy(String value) {
        if (value == null) return SceneConfig.PromptFilePolicy.APPEND;
        return switch (value.toLowerCase()) {
            case "override" -> SceneConfig.PromptFilePolicy.OVERRIDE;
            default -> SceneConfig.PromptFilePolicy.APPEND;
        };
    }

    private SceneConfig.AuditSpec toAuditSpec(SceneProperties props) {
        SceneConfig.AuditSpec spec = new SceneConfig.AuditSpec();
        if (props.getAuditEnabled() != null) spec.setEnabled(props.getAuditEnabled());
        spec.setTags(toSet(props.getAuditTags()));
        return spec;
    }

    private SceneConfig.RateLimitSpec toRateLimitSpec(SceneProperties props) {
        SceneConfig.RateLimitSpec spec = new SceneConfig.RateLimitSpec();
        if (props.getRateLimitEnabled() != null) spec.setEnabled(props.getRateLimitEnabled());
        spec.setRequestsPerMinute(props.getRateLimitRequestsPerMinute());
        spec.setBurst(props.getRateLimitBurst());
        return spec;
    }

    private SceneConfig.BackendSpec toBackendSpec(SceneProperties props) {
        if (props.getDockerImage() == null && props.getDockerMemory() == null
                && props.getDockerCpus() == null && props.getDockerWorkdir() == null
                && props.getSshHost() == null) {
            return null;
        }
        SceneConfig.BackendSpec spec = new SceneConfig.BackendSpec();
        spec.setDockerImage(props.getDockerImage());
        spec.setDockerMemory(props.getDockerMemory());
        spec.setDockerCpus(props.getDockerCpus());
        spec.setDockerPersistFilesystem(Boolean.TRUE.equals(props.getDockerPersistFilesystem()));
        spec.setDockerWorkdir(props.getDockerWorkdir());
        spec.setSshHost(props.getSshHost());
        if (props.getSshPort() != null) spec.setSshPort(props.getSshPort());
        spec.setSshUser(props.getSshUser());
        spec.setSshIdentityFile(props.getSshIdentityFile());
        return spec;
    }

    private SceneConfig.StorageSpec toStorageSpec(SceneProperties props) {
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
        SceneConfig.StorageSpec spec = new SceneConfig.StorageSpec();
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
    public static class SceneProperties {
        private String systemPrompt;
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
}