package io.github.huskyagent.application.session;

import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.application.runtime.RuntimeBackendCapabilityResolver;
import io.github.huskyagent.domain.context.ContextManager;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.context.ContextStatus;
import io.github.huskyagent.infra.session.CheckpointStore;
import io.github.huskyagent.infra.session.CheckpointStoreFactory;
import io.github.huskyagent.infra.session.MessageEntity;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionRepository;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionOperationsService {

    private final SessionManager sessionManager;
    private final CheckpointStoreFactory checkpointStoreFactory;
    private final ContextManager contextManager;
    private final SessionRepository sessionRepository;
    private final SceneResolver sceneResolver;
    private final RuntimePolicyResolver runtimePolicyResolver;
    private final RuntimeBackendCapabilityResolver backendCapabilities;
    private final ToolRegistry toolRegistry;

    public int countMessages(String sessionId) {
        return sessionManager.countMessages(sessionId);
    }

    public List<MessageEntity> listUserMessages(String sessionId) {
        return sessionManager.listUserMessages(sessionId);
    }

    public void rewindTo(String sessionId, long afterMessageId) {
        CheckpointStore store = checkpointStore(sessionId);
        String targetCheckpointId = sessionManager.getCheckpointIdForMessage(sessionId, afterMessageId);
        boolean truncated = sessionManager.rewindTo(sessionId, afterMessageId);
        if (!truncated) {
            log.info("[rewind] session={} already at last round, no checkpoint update needed", sessionId);
            return;
        }

        if (targetCheckpointId == null) {
            log.warn("[rewind] no checkpoint_id recorded for message={}, session={} - checkpoint not rolled back",
                    afterMessageId, sessionId);
            return;
        }

        store.deleteCheckpointsAfter(sessionId, targetCheckpointId);
        log.info("[rewind] session={} rolled back to checkpointId={}", sessionId, targetCheckpointId);
    }

    private CheckpointStore checkpointStore(String sessionId) {
        return checkpointStoreFactory.forSessionScope(resolveSessionScope(sessionId));
    }

    private SessionScope resolveSessionScope(String sessionId) {
        SessionScope current = SessionContext.getScope();
        if (current != null) {
            return current;
        }

        String sceneId = sessionRepository.getSessionScene(sessionId);
        if (sceneId == null || sceneId.isBlank()) {
            log.warn("[rewind] no scene_id recorded for session={} - using local checkpoint store", sessionId);
            return SessionScope.builder()
                    .sessionId(sessionId)
                    .checkpointType("local")
                    .workspaceType("local")
                    .backendType("local")
                    .filesystemAvailable(true)
                    .build();
        }

        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        var runtimePolicy = runtimePolicyResolver.resolve(sceneConfig, toolRegistry.getAllEnabled());
        return SessionScope.builder()
                .sessionId(sessionId)
                .sceneId(sceneId)
                .checkpointType(runtimePolicy.effectiveCheckpointType())
                .workspaceType(runtimePolicy.effectiveWorkspaceType())
                .backendType(backendCapabilities.backendType(runtimePolicy))
                .filesystemAvailable(backendCapabilities.filesystemAvailable(runtimePolicy))
                .build();
    }

    public ContextStatus getContextStatus() {
        return contextManager.getStatus();
    }

    public ContextStatus getContextStatus(String sessionId) {
        return contextManager.getStatus(sessionId);
    }
}
