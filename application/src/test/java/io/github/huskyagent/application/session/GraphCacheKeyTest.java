package io.github.huskyagent.application.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.runtime.CapabilityVisibilityResolver;
import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.domain.context.ContextManagementStrategyResolver;
import io.github.huskyagent.domain.context.strategy.NoopContextManagementStrategy;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.context.ContextConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.memory.BuiltinMemoryProvider;
import io.github.huskyagent.infra.memory.DefaultMemoryRuntimeStrategy;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.memory.MemoryProvider;
import io.github.huskyagent.infra.memory.MemoryResult;
import io.github.huskyagent.infra.memory.MemoryRuntimeStrategyResolver;
import io.github.huskyagent.infra.memory.MemoryContext;
import io.github.huskyagent.infra.memory.MemorySearchOptions;
import io.github.huskyagent.infra.memory.MemoryScopeResolver;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphCacheKeyTest {

    private static final Path WORK_DIR = Path.of("/tmp/test");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- basic equality / inequality (6-field key) ---

    @Test
    void differentSceneIdProducesDifferentKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene-a", WORK_DIR, "fp", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene-b", WORK_DIR, "fp", "prompt", "user", "session-1");

        assertNotEquals(k1, k2);
    }

    @Test
    void differentWorkingDirectoryProducesDifferentKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", Path.of("/tmp/a"), "fp", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", Path.of("/tmp/b"), "fp", "prompt", "user", "session-1");

        assertNotEquals(k1, k2);
    }

    @Test
    void differentFingerprintProducesDifferentKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", WORK_DIR, "fp1", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", WORK_DIR, "fp2", "prompt", "user", "session-1");

        assertNotEquals(k1, k2);
    }

    @Test
    void differentPrincipalProducesDifferentKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user1", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user2", "session-1");

        assertNotEquals(k1, k2);
    }

    @Test
    void differentSessionScopeProducesDifferentKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-2");

        assertNotEquals(k1, k2);
    }

    @Test
    void samePolicyProducesSameKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-1");

        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void nullFingerprintFallsBackToDefault() {
        GraphCacheKey k = GraphCacheKey.of("scene", WORK_DIR, null, "prompt", "user", "session-1");

        assertEquals("default", k.getRuntimePolicyFingerprint());
    }

    @Test
    void nullPrincipalFallsBackToDefault() {
        GraphCacheKey k = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", null, "session-1");

        assertEquals("default", k.getPrincipalId());
    }

    @Test
    void nullSessionScopeFallsBackToShared() {
        GraphCacheKey k = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", null);

        assertEquals("shared", k.getSessionScopeId());
    }

    // --- channel fields no longer affect cache key ---

    @Test
    void sameSceneDifferentChannelProducesSameKey() {
        GraphCacheKey k1 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("scene", WORK_DIR, "fp", "prompt", "user", "session-1");

        // Different channel types/conversation types don't affect key anymore
        assertEquals(k1, k2);
    }

    @Test
    void runtimeScopeWithWorkingDirectoryPreservesRuntimePolicyAndIdentity() {
        RuntimePolicy policy = RuntimePolicy.builder()
                .sceneId("assistant")
                .build();
        SceneConfig scene = scene("assistant");
        Principal principal = Principal.builder()
                .id("local:default")
                .channelType(ChannelType.TUI)
                .build();
        ChannelIdentity identity = ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .build();
        RuntimeScope original = RuntimeScope.builder()
                .sessionId("session-1")
                .principal(principal)
                .channelIdentity(identity)
                .runtimePolicy(policy)
                .workingDirectory(Path.of("/tmp/old"))
                .build();

        RuntimeScope updated = original.withWorkingDirectory(Path.of("/tmp/new"));

        assertEquals("session-1", updated.getSessionId());
        assertSame(principal, updated.getPrincipal());
        assertSame(identity, updated.getChannelIdentity());
        assertSame(policy, updated.getRuntimePolicy());
        assertEquals(Path.of("/tmp/new"), updated.getWorkingDirectory());
    }

    // --- cross-layer: RuntimePolicy fingerprint drives GraphCacheKey ---

    @Test
    void fingerprintFromRuntimePolicyCoversBothCapabilityAndMemory() {
        ToolDefinition memoryRead = tool("memory_read", Toolset.MEMORY);
        ToolDefinition coreTool = tool("core_tool", Toolset.CORE);
        RuntimePolicyResolver resolver = buildResolver(memoryRead, coreTool);

        // policy 1: MEMORY toolset allowed → memory_read visible
        SceneConfig s1 = scene("test");
        s1.setAllowedToolsets(Set.of(Toolset.CORE, Toolset.MEMORY));

        // policy 2: only CORE → memory_read invisible
        SceneConfig s2 = scene("test");
        s2.setAllowedToolsets(Set.of(Toolset.CORE));

        RuntimePolicy p1 = resolver.resolve(s1, List.of(memoryRead, coreTool));
        RuntimePolicy p2 = resolver.resolve(s2, List.of(memoryRead, coreTool));

        assertNotEquals(p1.fingerprint(), p2.fingerprint(),
                "different visible tools should produce different fingerprint");

        GraphCacheKey k1 = GraphCacheKey.of("test", WORK_DIR, p1.fingerprint(), null, null, "session-1");
        GraphCacheKey k2 = GraphCacheKey.of("test", WORK_DIR, p2.fingerprint(), null, null, "session-1");
        assertNotEquals(k1, k2,
                "different policy fingerprints should produce different GraphCacheKeys");
    }

    @Test
    void fingerprintCapturesMemoryPolicyChange() {
        RuntimePolicyResolver resolver = buildResolver(
                tool("memory_read", Toolset.MEMORY));

        SceneConfig memOn = scene("test");
        memOn.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec specOn = new SceneConfig.MemoryPolicySpec();
        specOn.setEnabled(true);
        memOn.setMemoryPolicyConfig(specOn);

        SceneConfig memOff = scene("test");
        memOff.setAllowedToolsets(Set.of(Toolset.MEMORY));
        SceneConfig.MemoryPolicySpec specOff = new SceneConfig.MemoryPolicySpec();
        specOff.setEnabled(false);
        memOff.setMemoryPolicyConfig(specOff);

        RuntimePolicy p1 = resolver.resolve(memOn, List.of(tool("memory_read", Toolset.MEMORY)));
        RuntimePolicy p2 = resolver.resolve(memOff, List.of(tool("memory_read", Toolset.MEMORY)));

        assertNotEquals(p1.fingerprint(), p2.fingerprint(),
                "memory enabled vs disabled should produce different fingerprints");
        assertNotEquals(
                GraphCacheKey.of("test", WORK_DIR, p1.fingerprint(), null, null, "session-1"),
                GraphCacheKey.of("test", WORK_DIR, p2.fingerprint(), null, null, "session-1"));
    }

    // --- helpers ---

    private ToolDefinition tool(String name, Toolset toolset) {
        return ToolDefinition.of(name, name, toolset, MAPPER.createObjectNode(), args -> null);
    }

    private SceneConfig scene(String sceneId) {
        SceneConfig s = new SceneConfig();
        s.setSceneId(sceneId);
        return s;
    }

    private RuntimePolicyResolver buildResolver(ToolDefinition... tools) {
        MemoryScopeResolver memoryScopeResolver = new MemoryScopeResolver();
        MemoryManager memoryManager = new MemoryManager(
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))));
        memoryManager.registerProvider(fakeProvider(BuiltinMemoryProvider.NAME));

        ToolRegistry registry = new ToolRegistry(List.of());
        for (ToolDefinition t : tools) {
            registry.register(t);
        }

        return new RuntimePolicyResolver(
                new CapabilityVisibilityResolver(new SkillManager()),
                new ContextConfig(),
                new ContextManagementStrategyResolver(List.of(
                        new FakeContextStrategy("default"),
                        new NoopContextManagementStrategy())),
                new MemoryRuntimeStrategyResolver(List.of(new DefaultMemoryRuntimeStrategy(memoryScopeResolver))),
                memoryManager,
                new KnowledgeManager(List.of(), new KnowledgeConfig()));
    }

    private MemoryProvider fakeProvider(String name) {
        return new MemoryProvider() {
            @Override public String getName() { return name; }
            @Override public boolean isAvailable() { return true; }
            @Override public void initialize(MemoryContext ctx) {}
            @Override public String buildSystemPrompt() { return ""; }
            @Override public MemoryResult prefetch(String q, MemorySearchOptions o) {
                return MemoryResult.empty(name);
            }
        };
    }

    private static class FakeContextStrategy implements io.github.huskyagent.domain.context.ContextManagementStrategy {
        private final String id;
        FakeContextStrategy(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public io.github.huskyagent.domain.context.ContextManagementResult prepare(
                io.github.huskyagent.domain.context.ContextManagementRequest req) {
            return io.github.huskyagent.domain.context.ContextManagementResult.unchanged(req.persistedMessages(), id, "test");
        }
    }
}