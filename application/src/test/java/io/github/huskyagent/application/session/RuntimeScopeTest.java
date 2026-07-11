package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeScopeTest {

    @Test
    void completeScopePassesExecutionValidation() {
        assertDoesNotThrow(() -> RuntimeScopeTestFixtures.completeScope().requireCompleteForExecution());
    }

    @Test
    void missingRuntimePolicyFailsFast() {
        RuntimeScope complete = RuntimeScopeTestFixtures.completeScope();
        RuntimeScope scope = RuntimeScope.builder()
                .sessionId(complete.getSessionId())
                .principal(complete.getPrincipal())
                .channelIdentity(complete.getChannelIdentity())
                .workingDirectory(complete.getWorkingDirectory())
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class, scope::requireCompleteForExecution);
        assertTrue(error.getMessage().contains("runtimePolicy"));
    }

    @Test
    void withWorkingDirectoryOnlyChangesWorkingDirectory() {
        RuntimeScope original = RuntimeScopeTestFixtures.completeScope();
        RuntimeScope updated = original.withWorkingDirectory(Path.of("/tmp/next"));

        assertEquals(original.getSessionId(), updated.getSessionId());
        assertSame(original.getPrincipal(), updated.getPrincipal());
        assertSame(original.getChannelIdentity(), updated.getChannelIdentity());
        assertSame(original.getRuntimePolicy(), updated.getRuntimePolicy());
        assertEquals(Path.of("/tmp/next"), updated.getWorkingDirectory());
    }

    @Test
    void toSessionScopeUsesRuntimePolicyAndScopeFields() {
        SessionScope sessionScope = RuntimeScopeTestFixtures.completeScope().toSessionScope();

        assertEquals("session-1", sessionScope.getSessionId());
        assertEquals("user-1", sessionScope.getPrincipalId());
        assertEquals("tui", sessionScope.getChannelType());
        assertEquals("assistant", sessionScope.getSceneId());
        assertEquals("/tmp/work", sessionScope.getWorkingDirectory());
        assertEquals("SESSION", sessionScope.getMemoryPolicy());
        assertEquals("custom", sessionScope.getMemoryStrategyId());
        assertEquals("FULL", sessionScope.getMemoryPromptMode());
        assertEquals(Set.of("builtin"), sessionScope.getMemoryProviderIds());
        assertTrue(sessionScope.isMemoryWriteAllowed());
        assertTrue(sessionScope.isAllowCrossSessionMemorySearch());
        assertEquals(Set.of("review"), sessionScope.getVisibleSkillNames());
        assertEquals(Set.of("docs"), sessionScope.getKnowledgeSourceIds());
        assertEquals("local", sessionScope.getBackendType());
        assertTrue(sessionScope.getFilesystemAvailable());
        assertEquals("local", sessionScope.getWorkspaceType());
        assertEquals("local", sessionScope.getCheckpointType());
    }

    @Test
    void toSessionScopeMarksDockerPersistentFilesystemAvailable() {
        var base = RuntimeScopeTestFixtures.completeScope();
        var spec = new io.github.huskyagent.domain.scene.SceneConfig.BackendSpec();
        spec.setDockerPersistFilesystem(true);
        var policy = io.github.huskyagent.domain.runtime.RuntimePolicy.builder()
                .sceneId("assistant")
                .memoryPolicy(RuntimeScopeTestFixtures.runtimePolicy().getMemoryPolicy())
                .capabilityView(RuntimeScopeTestFixtures.runtimePolicy().getCapabilityView())
                .knowledgeSources(Set.of("docs"))
                .backendPolicy(io.github.huskyagent.domain.scene.SceneConfig.BackendPolicy.DOCKER)
                .backendSpec(spec)
                .build();
        RuntimeScope scope = RuntimeScope.builder()
                .sessionId(base.getSessionId())
                .principal(base.getPrincipal())
                .channelIdentity(base.getChannelIdentity())
                .runtimePolicy(policy)
                .workingDirectory(base.getWorkingDirectory())
                .filesystemAvailable(true)
                .build();

        SessionScope sessionScope = scope.toSessionScope();

        assertEquals("docker", sessionScope.getBackendType());
        assertTrue(sessionScope.getFilesystemAvailable());
        assertEquals("/workspace", sessionScope.getRuntimeWorkingDirectory());
    }

    @Test
    void toSessionScopeMarksSshFilesystemUnavailable() {
        var base = RuntimeScopeTestFixtures.completeScope();
        var policy = io.github.huskyagent.domain.runtime.RuntimePolicy.builder()
                .sceneId("assistant")
                .memoryPolicy(RuntimeScopeTestFixtures.runtimePolicy().getMemoryPolicy())
                .capabilityView(RuntimeScopeTestFixtures.runtimePolicy().getCapabilityView())
                .knowledgeSources(Set.of("docs"))
                .backendPolicy(io.github.huskyagent.domain.scene.SceneConfig.BackendPolicy.SSH)
                .build();
        RuntimeScope scope = RuntimeScope.builder()
                .sessionId(base.getSessionId())
                .principal(base.getPrincipal())
                .channelIdentity(base.getChannelIdentity())
                .runtimePolicy(policy)
                .workingDirectory(base.getWorkingDirectory())
                .build();

        SessionScope sessionScope = scope.toSessionScope();

        assertEquals("ssh", sessionScope.getBackendType());
        assertFalse(sessionScope.getFilesystemAvailable());
    }

    @Test
    void toSessionScopeUsesRemoteStorageSpecWhenConfigured() {
        var base = RuntimeScopeTestFixtures.completeScope();
        var basePolicy = RuntimeScopeTestFixtures.runtimePolicy();
        var storageSpec = new io.github.huskyagent.domain.scene.SceneConfig.StorageSpec();
        storageSpec.setWorkspaceType("s3");
        storageSpec.setCheckpointType("postgres");
        var policy = io.github.huskyagent.domain.runtime.RuntimePolicy.builder()
                .sceneId(basePolicy.getSceneId())
                .memoryPolicy(basePolicy.getMemoryPolicy())
                .capabilityView(basePolicy.getCapabilityView())
                .knowledgeSources(basePolicy.getKnowledgeSources())
                .storagePolicy(io.github.huskyagent.domain.scene.SceneConfig.StoragePolicy.REMOTE)
                .storageSpec(storageSpec)
                .build();
        RuntimeScope scope = RuntimeScope.builder()
                .sessionId(base.getSessionId())
                .principal(base.getPrincipal())
                .channelIdentity(base.getChannelIdentity())
                .runtimePolicy(policy)
                .workingDirectory(base.getWorkingDirectory())
                .build();

        SessionScope sessionScope = scope.toSessionScope();

        assertEquals("s3", sessionScope.getWorkspaceType());
        assertEquals("postgres", sessionScope.getCheckpointType());
    }

    @Test
    void toSessionScopeForcesLocalWhenStoragePolicyIsLocal() {
        var base = RuntimeScopeTestFixtures.completeScope();
        var basePolicy = RuntimeScopeTestFixtures.runtimePolicy();
        var storageSpec = new io.github.huskyagent.domain.scene.SceneConfig.StorageSpec();
        storageSpec.setWorkspaceType("s3");
        storageSpec.setCheckpointType("postgres");
        var policy = io.github.huskyagent.domain.runtime.RuntimePolicy.builder()
                .sceneId(basePolicy.getSceneId())
                .memoryPolicy(basePolicy.getMemoryPolicy())
                .capabilityView(basePolicy.getCapabilityView())
                .knowledgeSources(basePolicy.getKnowledgeSources())
                .storagePolicy(io.github.huskyagent.domain.scene.SceneConfig.StoragePolicy.LOCAL)
                .storageSpec(storageSpec)
                .build();
        RuntimeScope scope = RuntimeScope.builder()
                .sessionId(base.getSessionId())
                .principal(base.getPrincipal())
                .channelIdentity(base.getChannelIdentity())
                .runtimePolicy(policy)
                .workingDirectory(base.getWorkingDirectory())
                .build();

        SessionScope sessionScope = scope.toSessionScope();

        assertEquals("local", sessionScope.getWorkspaceType());
        assertEquals("local", sessionScope.getCheckpointType());
    }
}
