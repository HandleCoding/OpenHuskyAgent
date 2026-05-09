package io.github.huskyagent.domain.scene;

import io.github.huskyagent.infra.tool.Toolset;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Scene is the runtime shape of an assistant product: prompt, capabilities, memory,
 * context, approval, backend, and working-directory policy.
 */
@Data
public class SceneConfig {

    private String sceneId;
    private String systemPrompt;

    private Set<Toolset> allowedToolsets = Set.of(Toolset.values());
    private Set<String> allowedTools = Set.of();
    private Set<String> deniedTools = Set.of();
    private Set<String> allowedMcpServers = Set.of();
    private Set<String> deniedMcpServers = Set.of();
    private Set<String> knowledgeSources = Set.of();
    private Set<String> skillIds = Set.of();
    private Set<String> promptSections = Set.of();

    private ApprovalPolicy approvalPolicy = ApprovalPolicy.REQUIRED;
    private BackendPolicy backendPolicy = BackendPolicy.LOCAL;
    private WorkingDirectoryPolicy workingDirectoryPolicy = WorkingDirectoryPolicy.INHERIT;
    private String fixedWorkingDirectory;
    private BackendSpec backendSpec;
    private StoragePolicy storagePolicy = StoragePolicy.LOCAL;
    private StorageSpec storageSpec;

    private List<String> promptFiles = List.of();
    private PromptFilePolicy promptFilePolicy = PromptFilePolicy.APPEND;

    private ContextPolicySpec contextPolicy = new ContextPolicySpec();
    private MemoryPolicySpec memoryPolicyConfig = new MemoryPolicySpec();
    private AuditSpec auditSpec = new AuditSpec();
    private RateLimitSpec rateLimitSpec = new RateLimitSpec();

    public LegacyMemoryPolicy getMemoryPolicy() {
        MemoryPolicySpec spec = memoryPolicyConfig != null ? memoryPolicyConfig : new MemoryPolicySpec();
        if (!spec.isEnabled() || spec.getAccess() == MemoryAccess.DISABLED) {
            return LegacyMemoryPolicy.DISABLED;
        }
        if (spec.getAccess() == MemoryAccess.READONLY) {
            return LegacyMemoryPolicy.READONLY;
        }
        return switch (spec.getScope()) {
            case USER_PROFILE -> LegacyMemoryPolicy.USER_PROFILE;
            case PRINCIPAL -> LegacyMemoryPolicy.PRINCIPAL;
            case SCENE -> LegacyMemoryPolicy.SCENE;
            default -> LegacyMemoryPolicy.SESSION;
        };
    }

    public void setMemoryPolicy(LegacyMemoryPolicy policy) {
        MemoryPolicySpec spec = memoryPolicyConfig != null ? memoryPolicyConfig : new MemoryPolicySpec();
        if (policy == null) {
            policy = LegacyMemoryPolicy.SESSION;
        }
        switch (policy) {
            case DISABLED -> {
                spec.setEnabled(false);
                spec.setAccess(MemoryAccess.DISABLED);
            }
            case READONLY -> {
                spec.setEnabled(true);
                spec.setAccess(MemoryAccess.READONLY);
            }
            case USER_PROFILE -> {
                spec.setEnabled(true);
                spec.setAccess(MemoryAccess.READWRITE);
                spec.setScope(MemoryScopePolicy.USER_PROFILE);
            }
            case PRINCIPAL -> {
                spec.setEnabled(true);
                spec.setAccess(MemoryAccess.READWRITE);
                spec.setScope(MemoryScopePolicy.PRINCIPAL);
            }
            case SCENE -> {
                spec.setEnabled(true);
                spec.setAccess(MemoryAccess.READWRITE);
                spec.setScope(MemoryScopePolicy.SCENE);
            }
            default -> {
                spec.setEnabled(true);
                spec.setAccess(MemoryAccess.READWRITE);
                spec.setScope(MemoryScopePolicy.SESSION);
            }
        }
        memoryPolicyConfig = spec;
    }

    public boolean isMemoryWriteAllowed() {
        return memoryPolicyConfig != null && memoryPolicyConfig.isEnabled()
                && memoryPolicyConfig.getAccess() == MemoryAccess.READWRITE;
    }

    public enum ApprovalPolicy {
        REQUIRED,
        NONE,
        AUTO_APPROVE_SAFE
    }

    public enum BackendPolicy {
        LOCAL,
        DOCKER,
        SSH
    }

    public enum StoragePolicy {
        LOCAL,
        REMOTE
    }

    public enum WorkingDirectoryPolicy {
        INHERIT,
        FIXED
    }

    public enum PromptFilePolicy {
        APPEND,
        OVERRIDE
    }

    public enum MemoryAccess {
        DISABLED,
        READONLY,
        READWRITE
    }

    public enum MemoryScopePolicy {
        SESSION,
        PRINCIPAL,
        SCENE,
        USER_PROFILE
    }

    public enum LegacyMemoryPolicy {
        DISABLED,
        READONLY,
        SESSION,
        USER_PROFILE,
        PRINCIPAL,
        SCENE
    }

    public enum MemoryPromptMode {
        NONE,
        SUMMARY,
        FULL,
        PROFILE_ONLY
    }

    @Data
    public static class BackendSpec {
        private String dockerImage;
        private String dockerMemory;
        private String dockerCpus;
        private boolean dockerPersistFilesystem = false;
        private String dockerWorkdir;
        private String sshHost;
        private int sshPort = 22;
        private String sshUser;
        private String sshIdentityFile;
    }

    @Data
    public static class StorageSpec {
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
    public static class ContextPolicySpec {
        private boolean enabled = true;
        private String mode = "prune-then-summary";
        private String strategy = "default";
        private String pruneStrategy = "default";
        private String summaryStrategy = "default";
        private Double thresholdPercent;
        private Integer contextLength;
        private Integer protectFirstN;
        private Integer tailTokenBudget;
        private Integer maxSummaryTokens;
        private String summaryFocus;
    }

    @Data
    public static class MemoryPolicySpec {
        private boolean enabled = true;
        private String strategy = "default";
        private MemoryAccess access = MemoryAccess.READWRITE;
        private MemoryScopePolicy scope = MemoryScopePolicy.SESSION;
        private Set<String> providers = Set.of();
        private MemoryPromptMode promptMode = MemoryPromptMode.SUMMARY;
        private boolean allowCrossSessionSearch = false;
    }

    @Data
    public static class AuditSpec {
        private boolean enabled = true;
        private Set<String> tags = Set.of();
    }

    @Data
    public static class RateLimitSpec {
        private boolean enabled = false;
        private Integer requestsPerMinute;
        private Integer burst;
    }
}
