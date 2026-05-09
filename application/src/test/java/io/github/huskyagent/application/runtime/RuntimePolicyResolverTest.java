package io.github.huskyagent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.domain.context.ContextManagementStrategyResolver;
import io.github.huskyagent.domain.context.strategy.NoopContextManagementStrategy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.context.ContextConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.memory.BuiltinMemoryProvider;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.memory.DefaultMemoryRuntimeStrategy;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.memory.MemoryProvider;
import io.github.huskyagent.infra.memory.MemoryRuntimeStrategyResolver;
import io.github.huskyagent.infra.memory.MemoryScopeResolver;
import io.github.huskyagent.infra.memory.SessionMemoryProvider;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePolicyResolverTest {

    private List<ToolDefinition> candidateTools = List.of();

    @Test
    void resolvesContextAndMemoryStrategyPolicy() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME, SessionMemoryProvider.NAME);
        SceneConfig scene = baseScene();
        scene.getContextPolicy().setStrategy("none");
        scene.getMemoryPolicyConfig().setStrategy("default");
        scene.getMemoryPolicyConfig().setProviders(Set.of(SessionMemoryProvider.NAME));

        var policy = resolve(resolver, scene);

        assertEquals("none", policy.getContextPolicy().getStrategyId());
        assertEquals("default", policy.getMemoryPolicy().getStrategyId());
        assertEquals(Set.of(SessionMemoryProvider.NAME), policy.getMemoryPolicy().getProviders());
    }

    @Test
    void rejectsUnknownContextStrategy() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig scene = baseScene();
        scene.getContextPolicy().setStrategy("missing");

        assertThrows(IllegalArgumentException.class, () -> resolve(resolver, scene));
    }

    @Test
    void rejectsUnknownMemoryProvider() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig scene = baseScene();
        scene.getMemoryPolicyConfig().setProviders(Set.of("missing"));

        assertThrows(IllegalArgumentException.class, () -> resolve(resolver, scene));
    }

    @Test
    void fingerprintChangesWhenStrategyChanges() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig left = baseScene();
        left.getContextPolicy().setStrategy("default");
        SceneConfig right = baseScene();
        right.getContextPolicy().setStrategy("none");

        assertNotEquals(resolve(resolver, left).fingerprint(), resolve(resolver, right).fingerprint());
    }

    @Test
    void modelSpecificContextLengthOverridesGlobalFallback() {
        ContextConfig contextConfig = new ContextConfig();
        contextConfig.setContextLength(128000);
        contextConfig.setModelContextLengths(Map.of("large-model", 200000));
        RuntimePolicyResolver resolver = resolverWithContextConfig(contextConfig, "large-model");

        var policy = resolve(resolver, baseScene());

        assertEquals(200000, policy.getContextPolicy().getContextLength());
    }

    @Test
    void unknownModelUsesGlobalContextLengthFallback() {
        ContextConfig contextConfig = new ContextConfig();
        contextConfig.setContextLength(128000);
        contextConfig.setModelContextLengths(Map.of("large-model", 200000));
        RuntimePolicyResolver resolver = resolverWithContextConfig(contextConfig, "unknown-model");

        var policy = resolve(resolver, baseScene());

        assertEquals(128000, policy.getContextPolicy().getContextLength());
    }

    @Test
    void sceneContextLengthOverridesModelSpecificMapping() {
        ContextConfig contextConfig = new ContextConfig();
        contextConfig.setContextLength(128000);
        contextConfig.setModelContextLengths(Map.of("large-model", 200000));
        RuntimePolicyResolver resolver = resolverWithContextConfig(contextConfig, "large-model");
        SceneConfig scene = baseScene();
        scene.getContextPolicy().setContextLength(64000);

        var policy = resolve(resolver, scene);

        assertEquals(64000, policy.getContextPolicy().getContextLength());
    }

    // --- memory visibility tests ---

    @Test
    void memoryDisabledHidesMemoryToolsFromCapabilityView() {
        ToolDefinition memoryRead = tool("memory_read", Toolset.MEMORY);
        ToolDefinition coreOther = tool("list_files", Toolset.CORE);
        RuntimePolicyResolver resolver = resolverWithProvidersAndTools(
                List.of(memoryRead, coreOther), BuiltinMemoryProvider.NAME, SessionMemoryProvider.NAME);

        SceneConfig scene = baseScene();
        scene.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(false);
        scene.setMemoryPolicyConfig(mem);

        var policy = resolve(resolver, scene);

        assertFalse(policy.getCapabilityView().getVisibleToolNames().contains("memory_read"),
                "memory_read should be hidden when memory disabled");
        assertTrue(policy.getCapabilityView().getVisibleToolNames().contains("list_files"),
                "non-memory tools should still be visible");
    }

    @Test
    void sessionProviderOnlyHidesBuiltinTools() {
        ToolDefinition memoryRead = tool("memory_read", Toolset.MEMORY);
        ToolDefinition sessionSearch = tool("session_search", Toolset.MEMORY);
        RuntimePolicyResolver resolver = resolverWithProvidersAndTools(
                List.of(memoryRead, sessionSearch), BuiltinMemoryProvider.NAME, SessionMemoryProvider.NAME);

        SceneConfig scene = baseScene();
        scene.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec mem = new SceneConfig.MemoryPolicySpec();
        mem.setEnabled(true);
        mem.setProviders(Set.of(SessionMemoryProvider.NAME));
        scene.setMemoryPolicyConfig(mem);

        var policy = resolve(resolver, scene);

        assertFalse(policy.getCapabilityView().getVisibleToolNames().contains("memory_read"),
                "builtin tool should be hidden when builtin provider not in whitelist");
        assertTrue(policy.getCapabilityView().getVisibleToolNames().contains("session_search"),
                "session tool should be visible");
    }

    @Test
    void fingerprintChangesWhenMemoryEnabledChanges() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig enabled = baseScene();
        SceneConfig.MemoryPolicySpec memOn = new SceneConfig.MemoryPolicySpec();
        memOn.setEnabled(true);
        enabled.setMemoryPolicyConfig(memOn);

        SceneConfig disabled = baseScene();
        SceneConfig.MemoryPolicySpec memOff = new SceneConfig.MemoryPolicySpec();
        memOff.setEnabled(false);
        disabled.setMemoryPolicyConfig(memOff);

        assertNotEquals(resolve(resolver, enabled).fingerprint(), resolve(resolver, disabled).fingerprint());
    }

    @Test
    void fingerprintChangesWhenProviderWhitelistChanges() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME, SessionMemoryProvider.NAME);
        SceneConfig withBuiltin = baseScene();
        SceneConfig.MemoryPolicySpec m1 = new SceneConfig.MemoryPolicySpec();
        m1.setProviders(Set.of(BuiltinMemoryProvider.NAME));
        withBuiltin.setMemoryPolicyConfig(m1);

        SceneConfig withBoth = baseScene();
        SceneConfig.MemoryPolicySpec m2 = new SceneConfig.MemoryPolicySpec();
        m2.setProviders(Set.of(BuiltinMemoryProvider.NAME, SessionMemoryProvider.NAME));
        withBoth.setMemoryPolicyConfig(m2);

        assertNotEquals(resolve(resolver, withBuiltin).fingerprint(), resolve(resolver, withBoth).fingerprint());
    }

    @Test
    void carriesSceneExecutionFieldsIntoRuntimePolicy() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig scene = baseScene();
        SceneConfig.BackendSpec backendSpec = new SceneConfig.BackendSpec();
        backendSpec.setDockerImage("ubuntu:24.04");
        backendSpec.setDockerMemory("512m");
        backendSpec.setDockerCpus("1");
        backendSpec.setDockerPersistFilesystem(true);
        backendSpec.setDockerWorkdir("/workspace");
        SceneConfig.StorageSpec storageSpec = new SceneConfig.StorageSpec();
        storageSpec.setWorkspaceType("s3");
        storageSpec.setCheckpointType("postgres");

        scene.setSystemPrompt("Scene prompt");
        scene.setPromptFiles(List.of("CLAUDE.md", "AGENTS.md"));
        scene.setPromptFilePolicy(SceneConfig.PromptFilePolicy.OVERRIDE);
        scene.setBackendSpec(backendSpec);
        scene.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        scene.setStorageSpec(storageSpec);
        scene.setFixedWorkingDirectory("/tmp/husky");

        var policy = resolve(resolver, scene);

        assertEquals("Scene prompt", policy.getSystemPrompt());
        assertEquals(List.of("CLAUDE.md", "AGENTS.md"), policy.getPromptFiles());
        assertEquals(SceneConfig.PromptFilePolicy.OVERRIDE, policy.getPromptFilePolicy());
        assertSame(backendSpec, policy.getBackendSpec());
        assertEquals(SceneConfig.StoragePolicy.REMOTE, policy.getStoragePolicy());
        assertSame(storageSpec, policy.getStorageSpec());
        assertEquals("/tmp/husky", policy.getFixedWorkingDirectory());
    }

    @Test
    void localStorageDoesNotChangeFingerprint() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig base = baseScene();
        SceneConfig local = baseScene();
        SceneConfig.StorageSpec storageSpec = new SceneConfig.StorageSpec();
        storageSpec.setWorkspaceType("s3");
        storageSpec.setCheckpointType("postgres");
        local.setStoragePolicy(SceneConfig.StoragePolicy.LOCAL);
        local.setStorageSpec(storageSpec);

        var policy = resolve(resolver, local);

        assertEquals(resolve(resolver, base).fingerprint(), policy.fingerprint());
        assertEquals("local", policy.effectiveWorkspaceType());
        assertEquals("local", policy.effectiveCheckpointType());
    }

    @Test
    void remoteStorageNormalizesEffectiveTypesAndFingerprint() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig left = baseScene();
        left.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        SceneConfig.StorageSpec leftSpec = new SceneConfig.StorageSpec();
        leftSpec.setWorkspaceType(" S3 ");
        leftSpec.setCheckpointType(" POSTGRES ");
        left.setStorageSpec(leftSpec);

        SceneConfig right = baseScene();
        right.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        SceneConfig.StorageSpec rightSpec = new SceneConfig.StorageSpec();
        rightSpec.setWorkspaceType("s3");
        rightSpec.setCheckpointType("postgres");
        right.setStorageSpec(rightSpec);

        var policy = resolve(resolver, left);

        assertEquals("s3", policy.effectiveWorkspaceType());
        assertEquals("postgres", policy.effectiveCheckpointType());
        assertEquals(resolve(resolver, left).fingerprint(), resolve(resolver, right).fingerprint());
    }

    @Test
    void remoteStorageBlankTypesDefaultToLocal() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig scene = baseScene();
        scene.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        scene.setStorageSpec(new SceneConfig.StorageSpec());

        var policy = resolve(resolver, scene);

        assertEquals("local", policy.effectiveWorkspaceType());
        assertEquals("local", policy.effectiveCheckpointType());
    }

    @Test
    void fingerprintChangesWhenRemoteStorageSpecChanges() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig left = baseScene();
        left.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        SceneConfig.StorageSpec leftSpec = new SceneConfig.StorageSpec();
        leftSpec.setWorkspaceType("s3");
        leftSpec.setCheckpointType("postgres");
        left.setStorageSpec(leftSpec);

        SceneConfig right = baseScene();
        right.setStoragePolicy(SceneConfig.StoragePolicy.REMOTE);
        SceneConfig.StorageSpec rightSpec = new SceneConfig.StorageSpec();
        rightSpec.setWorkspaceType("gcs");
        rightSpec.setCheckpointType("redis");
        right.setStorageSpec(rightSpec);

        assertNotEquals(resolve(resolver, left).fingerprint(), resolve(resolver, right).fingerprint());
    }

    @Test
    void fingerprintChangesWhenPromptExecutionFieldsChange() {
        RuntimePolicyResolver resolver = resolverWithProviders(BuiltinMemoryProvider.NAME);
        SceneConfig left = baseScene();
        left.setSystemPrompt("left prompt");
        left.setPromptFiles(List.of("CLAUDE.md"));

        SceneConfig right = baseScene();
        right.setSystemPrompt("right prompt");
        right.setPromptFiles(List.of("AGENTS.md"));

        assertNotEquals(resolve(resolver, left).fingerprint(), resolve(resolver, right).fingerprint());
    }

    private io.github.huskyagent.domain.runtime.RuntimePolicy resolve(RuntimePolicyResolver resolver, SceneConfig scene, ToolDefinition... tools) {
        return resolver.resolve(scene, tools.length > 0 ? List.of(tools) : candidateTools);
    }

    private RuntimePolicyResolver resolverWithProvidersAndTools(List<ToolDefinition> tools, String... providerIds) {
        MemoryScopeResolver memoryScopeResolver = new MemoryScopeResolver();
        MemoryManager memoryManager = new MemoryManager(
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))));
        for (String providerId : providerIds) {
            memoryManager.registerProvider(provider(providerId));
        }
        SkillManager skillManager = new SkillManager();
        candidateTools = List.copyOf(tools);
        return new RuntimePolicyResolver(
                new CapabilityVisibilityResolver(skillManager),
                new ContextConfig(),
                new ContextManagementStrategyResolver(List.of(
                        new TestContextManagementStrategy("default"),
                        new NoopContextManagementStrategy())),
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))),
                memoryManager,
                new KnowledgeManager(List.of(), new KnowledgeConfig()));
    }

    private static ToolDefinition tool(String name, Toolset toolset) {
        return ToolDefinition.of(name, name, toolset, new ObjectMapper().createObjectNode(), args -> null);
    }

    private RuntimePolicyResolver resolverWithProviders(String... providerIds) {
        return resolverWithContextConfig(new ContextConfig(), null, providerIds);
    }

    private RuntimePolicyResolver resolverWithContextConfig(ContextConfig contextConfig, String modelName, String... providerIds) {
        MemoryScopeResolver memoryScopeResolver = new MemoryScopeResolver();
        MemoryManager memoryManager = new MemoryManager(
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))));
        for (String providerId : providerIds) {
            memoryManager.registerProvider(provider(providerId));
        }
        SkillManager skillManager = new SkillManager();
        RuntimePolicyResolver resolver = new RuntimePolicyResolver(
                new CapabilityVisibilityResolver(skillManager),
                contextConfig,
                new ContextManagementStrategyResolver(List.of(
                        new TestContextManagementStrategy("default"),
                        new NoopContextManagementStrategy())),
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))),
                memoryManager,
                new KnowledgeManager(List.of(), new KnowledgeConfig()));
        setModelName(resolver, modelName);
        return resolver;
    }

    private void setModelName(RuntimePolicyResolver resolver, String modelName) {
        try {
            Field field = RuntimePolicyResolver.class.getDeclaredField("modelName");
            field.setAccessible(true);
            field.set(resolver, modelName);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static class TestContextManagementStrategy implements io.github.huskyagent.domain.context.ContextManagementStrategy {
        private final String id;

        TestContextManagementStrategy(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public io.github.huskyagent.domain.context.ContextManagementResult prepare(io.github.huskyagent.domain.context.ContextManagementRequest request) {
            return io.github.huskyagent.domain.context.ContextManagementResult.unchanged(request.persistedMessages(), id, "test");
        }
    }

    private MemoryProvider provider(String id) {
        return new MemoryProvider() {
            @Override
            public String getName() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void initialize(io.github.huskyagent.infra.memory.MemoryContext context) {
            }

            @Override
            public String buildSystemPrompt() {
                return id;
            }

            @Override
            public io.github.huskyagent.infra.memory.MemoryResult prefetch(String query, io.github.huskyagent.infra.memory.MemorySearchOptions options) {
                return io.github.huskyagent.infra.memory.MemoryResult.empty(id);
            }
        };
    }

    private SceneConfig baseScene() {
        SceneConfig scene = new SceneConfig();
        scene.setSceneId("test");
        scene.setAllowedToolsets(Set.of(Toolset.CORE));
        return scene;
    }
}
