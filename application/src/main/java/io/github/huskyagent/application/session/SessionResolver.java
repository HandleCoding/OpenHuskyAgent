package io.github.huskyagent.application.session;

import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.application.runtime.RuntimeBackendCapabilityResolver;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.execute.BackendConfig;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.session.SessionEntity;
import io.github.huskyagent.infra.session.SessionRepository;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import io.github.huskyagent.domain.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionResolver {

    private final SessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final SceneResolver sceneResolver;
    private final RuntimePolicyResolver runtimePolicyResolver;
    private final ToolRegistry toolRegistry;
    private final ExecutionBackendFactory backendFactory;
    private final ExecutionBackendProperties backendProperties;
    private final RuntimeBackendCapabilityResolver backendCapabilities;
    private final SessionKeyStrategy sessionKeyStrategy;
    private final SessionAccessPolicy sessionAccessPolicy;

    public RuntimeScope resolveOrCreateSession(Principal principal, ChannelIdentity channelIdentity,
                                                String sceneId, String requestedSessionId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        RuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(sceneConfig, toolRegistry.getAllEnabled());
        IsolationScope requestedScope = IsolationScope.from(null, principal, channelIdentity, sceneConfig);
        String sessionKey = sessionKeyStrategy.buildKey(requestedScope);
        String sessionId;

        if (requestedSessionId != null) {
            SessionEntity existing = sessionRepository.findSession(requestedSessionId)
                    .orElseThrow(() -> new SecurityException("Session not found"));
            if (!sessionAccessPolicy.canResume(requestedScope, existing)) {
                log.warn("Session scope validation failed: sessionId={}, principalId={}, channel={}, scene={}",
                        requestedSessionId, principal.getId(), requestedScope.getChannelType(), sceneConfig.getSceneId());
                throw new SecurityException("Session is outside current isolation scope");
            }
            sessionId = requestedSessionId;
            updateIsolationMetadata(sessionId, requestedScope, sessionKey);
        } else {
            sessionId = sessionRepository.findBySessionKey(sessionKey)
                    .filter(existing -> sessionAccessPolicy.canResume(requestedScope, existing))
                    .map(SessionEntity::getId)
                    .orElseGet(() -> sessionManager.createSessionForUser(principal.getId()));
            updateIsolationMetadata(sessionId, requestedScope, sessionKey);
        }

        RuntimeScope scope = buildRuntimeScope(sessionId, principal, channelIdentity, runtimePolicy,
                resolveEffectiveWorkingDirectory(runtimePolicy, sessionId));
        registerBackend(scope);
        return scope;
    }

    public RuntimeScope createSession(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        RuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(sceneConfig, toolRegistry.getAllEnabled());
        IsolationScope requestedScope = IsolationScope.from(null, principal, channelIdentity, sceneConfig);
        String sessionKey = sessionKeyStrategy.buildKey(requestedScope);
        String sessionId = sessionManager.createSessionForUser(principal.getId());
        updateIsolationMetadata(sessionId, requestedScope, sessionKey);
        sessionRepository.deactivateOtherSessionsForKey(sessionKey, sessionId);

        RuntimeScope scope = buildRuntimeScope(sessionId, principal, channelIdentity, runtimePolicy,
                resolveEffectiveWorkingDirectory(runtimePolicy, sessionId));
        registerBackend(scope);
        return scope;
    }

    public RuntimeScope createEphemeralScope(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        RuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(sceneConfig, toolRegistry.getAllEnabled());
        String sessionId = UUID.randomUUID().toString();
        RuntimeScope scope = buildRuntimeScope(sessionId, principal, channelIdentity, runtimePolicy,
                resolveEffectiveWorkingDirectory(runtimePolicy, sessionId));
        registerBackend(scope);
        return scope;
    }

    public void releaseEphemeralScope(RuntimeScope scope) {
        if (scope != null && scope.getSessionId() != null) {
            backendFactory.release(scope.getSessionId());
        }
    }

    public Optional<String> findActiveSessionId(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        IsolationScope scope = IsolationScope.from(null, principal, channelIdentity, sceneConfig);
        String sessionKey = sessionKeyStrategy.buildKey(scope);
        return sessionRepository.findBySessionKey(sessionKey)
                .filter(existing -> sessionAccessPolicy.canResume(scope, existing))
                .map(SessionEntity::getId);
    }

    public List<SessionEntity> listSessions(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        IsolationScope scope = IsolationScope.from(null, principal, channelIdentity, sceneConfig);
        return sessionRepository.findSessionsByScope(scope.getPrincipalId(), scope.getChannelType(), scope.getSceneId())
                .stream()
                .filter(session -> sessionAccessPolicy.canList(scope, session))
                .toList();
    }

    private RuntimeScope buildRuntimeScope(String sessionId, Principal principal, ChannelIdentity channelIdentity,
                                           RuntimePolicy runtimePolicy, Path workingDirectory) {
        return RuntimeScope.builder()
                .sessionId(sessionId)
                .principal(principal)
                .channelIdentity(channelIdentity)
                .runtimePolicy(runtimePolicy)
                .workingDirectory(workingDirectory)
                .filesystemAvailable(backendCapabilities.filesystemAvailable(runtimePolicy))
                .build();
    }

    private void registerBackend(RuntimeScope scope) {
        backendFactory.registerSession(scope.getSessionId(),
                buildBackendConfig(scope.getRuntimePolicy(), scope.getSessionId(), scope.getWorkingDirectory().toString()));
        backendFactory.touchSession(scope.getSessionId());
    }

    private Path resolveEffectiveWorkingDirectory(RuntimePolicy runtimePolicy, String sessionId) {
        Path workingDirectory = resolveWorkingDirectory(runtimePolicy);
        Path dockerWorkspace = resolveDockerWorkspaceForFiles(runtimePolicy, sessionId);
        return dockerWorkspace != null ? dockerWorkspace : workingDirectory;
    }

    private Path resolveWorkingDirectory(RuntimePolicy runtimePolicy) {
        if (runtimePolicy.getWorkingDirectoryPolicy() == SceneConfig.WorkingDirectoryPolicy.FIXED
                && runtimePolicy.getFixedWorkingDirectory() != null) {
            return Path.of(runtimePolicy.getFixedWorkingDirectory());
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private Path resolveDockerWorkspaceForFiles(RuntimePolicy runtimePolicy, String sessionId) {
        if (runtimePolicy.getBackendPolicy() != SceneConfig.BackendPolicy.DOCKER) return null;
        if (!backendCapabilities.dockerPersistFilesystem(runtimePolicy.getBackendSpec())) return null;
        return dockerHostWorkspaceDir(sessionId);
    }

    private Path dockerHostWorkspaceDir(String sessionId) {
        String normalizedSessionId = sessionId != null ? sessionId.replace("-", "") : "";
        String suffix = normalizedSessionId.substring(0, Math.min(8, normalizedSessionId.length()));
        if (suffix.isBlank()) {
            suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return Path.of(backendProperties.getDocker().getWorkspaceRoot(), "husky-" + suffix);
    }

    private BackendConfig buildBackendConfig(RuntimePolicy runtimePolicy, String sessionId, String workDir) {
        SceneConfig.BackendSpec spec = runtimePolicy.getBackendSpec();
        ExecutionBackendProperties.DockerProperties docker = backendProperties.getDocker();
        ExecutionBackendProperties.SshProperties ssh = backendProperties.getSsh();
        boolean dockerPersistFilesystem = backendCapabilities.dockerPersistFilesystem(spec);

        return switch (runtimePolicy.getBackendPolicy()) {
            case DOCKER -> BackendConfig.builder()
                    .type("docker")
                    .initialWorkDir(spec != null && spec.getDockerWorkdir() != null ? spec.getDockerWorkdir() : "/workspace")
                    .dockerImage(spec != null && spec.getDockerImage() != null ? spec.getDockerImage() : docker.getImage())
                    .dockerMemory(spec != null && spec.getDockerMemory() != null ? spec.getDockerMemory() : docker.getMemory())
                    .dockerCpus(spec != null && spec.getDockerCpus() != null ? spec.getDockerCpus() : docker.getCpus())
                    .dockerPersistFilesystem(dockerPersistFilesystem)
                    .dockerWorkspaceRoot(docker.getWorkspaceRoot())
                    .dockerHostWorkspaceDir(dockerPersistFilesystem ? dockerHostWorkspaceDir(sessionId).toString() : null)
                    .build();
            case SSH -> BackendConfig.builder()
                    .type("ssh")
                    .initialWorkDir(workDir != null ? workDir : "~")
                    .sshHost(spec != null && spec.getSshHost() != null ? spec.getSshHost() : ssh.getHost())
                    .sshPort(spec != null ? spec.getSshPort() : ssh.getPort())
                    .sshUser(spec != null && spec.getSshUser() != null ? spec.getSshUser() : ssh.getUser())
                    .sshIdentityFile(spec != null && spec.getSshIdentityFile() != null ? spec.getSshIdentityFile() : ssh.getIdentityFile())
                    .sshWorkspaceRoot(ssh.getWorkspaceRoot())
                    .build();
            default -> BackendConfig.builder()
                    .type("local")
                    .initialWorkDir(workDir)
                    .build();
        };
    }

    private void updateIsolationMetadata(String sessionId, IsolationScope scope, String sessionKey) {
        sessionRepository.updateSessionIsolation(
                sessionId,
                scope.getPrincipalId(),
                scope.getChannelType(),
                scope.getSceneId(),
                scope.getConversationType(),
                scope.getChatId(),
                scope.getThreadId(),
                scope.getSenderId(),
                sessionKey);
    }

    public boolean isSessionOwnedBy(String sessionId, String principalId) {
        return sessionRepository.isSessionOwnedBy(sessionId, principalId);
    }
}
