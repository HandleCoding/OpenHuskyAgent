package io.github.huskyagent.application.session;

import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.application.runtime.RuntimeBackendCapabilityResolver;
import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.context.ContextManager;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.session.CheckpointStore;
import io.github.huskyagent.infra.session.CheckpointStoreFactory;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionRepository;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionOperationsServiceTest {

    @AfterEach
    void clearScope() {
        SessionContext.clear();
    }

    @Test
    void currentSessionScopeWinsWhenResolvingCheckpointStore() {
        SessionManager sessionManager = sessionManagerWithCheckpoint();
        CheckpointStoreFactory checkpointStoreFactory = mock(CheckpointStoreFactory.class);
        CheckpointStore store = mock(CheckpointStore.class);
        SessionScope scope = SessionScope.builder().sessionId("session-1").checkpointType("postgres").build();
        SessionContext.setScope(scope);
        when(checkpointStoreFactory.forSessionScope(scope)).thenReturn(store);
        SessionOperationsService service = service(sessionManager, checkpointStoreFactory,
                mock(SessionRepository.class), mock(SceneResolver.class), mock(RuntimePolicyResolver.class));

        service.rewindTo("session-1", 10L);

        verify(checkpointStoreFactory).forSessionScope(scope);
        verify(store).deleteCheckpointsAfter("session-1", "checkpoint-10");
    }

    @Test
    void noCurrentScopeResolvesCheckpointTypeFromStoredSessionScene() {
        SessionManager sessionManager = sessionManagerWithCheckpoint();
        CheckpointStoreFactory checkpointStoreFactory = mock(CheckpointStoreFactory.class);
        CheckpointStore store = mock(CheckpointStore.class);
        when(checkpointStoreFactory.forSessionScope(any(SessionScope.class))).thenReturn(store);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.getSessionScene("session-1")).thenReturn("remote-scene");
        SceneConfig scene = new SceneConfig();
        SceneResolver sceneResolver = mock(SceneResolver.class);
        when(sceneResolver.resolve("remote-scene")).thenReturn(scene);
        RuntimePolicyResolver runtimePolicyResolver = mock(RuntimePolicyResolver.class);
        when(runtimePolicyResolver.resolve(eq(scene), eq(List.of()))).thenReturn(remotePolicy());
        SessionOperationsService service = service(sessionManager, checkpointStoreFactory,
                sessionRepository, sceneResolver, runtimePolicyResolver);

        service.rewindTo("session-1", 10L);

        verify(checkpointStoreFactory).forSessionScope(org.mockito.ArgumentMatchers.argThat(scope ->
                "session-1".equals(scope.getSessionId())
                        && "remote-scene".equals(scope.getSceneId())
                        && "s3".equals(scope.getWorkspaceType())
                        && "postgres".equals(scope.getCheckpointType())));
        verify(store).deleteCheckpointsAfter("session-1", "checkpoint-10");
    }

    @Test
    void unsupportedCheckpointTypeFailsBeforeMessageTruncation() {
        SessionManager sessionManager = mock(SessionManager.class);
        CheckpointStoreFactory checkpointStoreFactory = mock(CheckpointStoreFactory.class);
        when(checkpointStoreFactory.forSessionScope(any(SessionScope.class)))
                .thenThrow(new IllegalArgumentException("Unsupported checkpoint store type: postgres"));
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.getSessionScene("session-1")).thenReturn("remote-scene");
        SceneConfig scene = new SceneConfig();
        SceneResolver sceneResolver = mock(SceneResolver.class);
        when(sceneResolver.resolve("remote-scene")).thenReturn(scene);
        RuntimePolicyResolver runtimePolicyResolver = mock(RuntimePolicyResolver.class);
        when(runtimePolicyResolver.resolve(eq(scene), eq(List.of()))).thenReturn(remotePolicy());
        SessionOperationsService service = service(sessionManager, checkpointStoreFactory,
                sessionRepository, sceneResolver, runtimePolicyResolver);

        assertThrows(IllegalArgumentException.class, () -> service.rewindTo("session-1", 10L));

        verify(sessionManager, never()).rewindTo("session-1", 10L);
    }

    private SessionOperationsService service(SessionManager sessionManager,
                                             CheckpointStoreFactory checkpointStoreFactory,
                                             SessionRepository sessionRepository,
                                             SceneResolver sceneResolver,
                                             RuntimePolicyResolver runtimePolicyResolver) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllEnabled()).thenReturn(List.of());
        return new SessionOperationsService(
                sessionManager,
                checkpointStoreFactory,
                mock(ContextManager.class),
                sessionRepository,
                sceneResolver,
                runtimePolicyResolver,
                new RuntimeBackendCapabilityResolver(new ExecutionBackendProperties()),
                toolRegistry);
    }

    private SessionManager sessionManagerWithCheckpoint() {
        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.getCheckpointIdForMessage("session-1", 10L)).thenReturn("checkpoint-10");
        when(sessionManager.rewindTo("session-1", 10L)).thenReturn(true);
        return sessionManager;
    }

    private RuntimePolicy remotePolicy() {
        SceneConfig.StorageSpec storageSpec = new SceneConfig.StorageSpec();
        storageSpec.setWorkspaceType("s3");
        storageSpec.setCheckpointType("postgres");
        return RuntimePolicy.builder()
                .sceneId("remote-scene")
                .capabilityView(CapabilityView.builder().build())
                .storagePolicy(SceneConfig.StoragePolicy.REMOTE)
                .storageSpec(storageSpec)
                .build();
    }
}
