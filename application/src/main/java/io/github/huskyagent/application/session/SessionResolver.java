package io.github.huskyagent.application.session;

import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
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
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import io.github.huskyagent.domain.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 会话解析器 — 统一入口，决定"谁拥有哪个 session、从哪个渠道来、属于哪个 scene"。
 *
 * <p>所有渠道（TUI / HTTP / 飞书 / Telegram）都走同一条解析链路。</p>
 */
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
    private final SessionKeyStrategy sessionKeyStrategy;
    private final SessionAccessPolicy sessionAccessPolicy;

    /**
     * 解析或创建会话，返回 RuntimeScope。
     *
     * @param principal       已认证主体
     * @param channelIdentity 渠道身份
     * @param sceneId         场景 ID（null 时 fallback 到默认 scene）
     * @param requestedSessionId 调用方请求恢复的 session ID（null 时新建）
     * @return RuntimeScope
     */
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
                log.warn("Session scope 校验失败: sessionId={}, principalId={}, channel={}, scene={}",
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

        Path workingDirectory = resolveWorkingDirectory(runtimePolicy);
        Path dockerWorkspace = resolveDockerWorkspaceForFiles(runtimePolicy, sessionId);
        if (dockerWorkspace != null) {
            workingDirectory = dockerWorkspace;
        }

        RuntimeScope scope = buildRuntimeScope(sessionId, principal, channelIdentity, runtimePolicy, workingDirectory);
        registerBackend(scope);
        return scope;
    }

    /**
     * 强制新建会话（不复用已有 session），返回 RuntimeScope。
     */
    public RuntimeScope createSession(Principal principal, ChannelIdentity channelIdentity, String sceneId) {
        SceneConfig sceneConfig = sceneResolver.resolve(sceneId);
        RuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(sceneConfig, toolRegistry.getAllEnabled());
        IsolationScope requestedScope = IsolationScope.from(null, principal, channelIdentity, sceneConfig);
        String sessionKey = sessionKeyStrategy.buildKey(requestedScope);
        String sessionId = sessionManager.createSessionForUser(principal.getId());
        updateIsolationMetadata(sessionId, requestedScope, sessionKey);
        sessionRepository.deactivateOtherSessionsForKey(sessionKey, sessionId);

        Path workingDirectory = resolveWorkingDirectory(runtimePolicy);
        Path dockerWorkspace = resolveDockerWorkspaceForFiles(runtimePolicy, sessionId);
        if (dockerWorkspace != null) {
            workingDirectory = dockerWorkspace;
        }

        RuntimeScope scope = buildRuntimeScope(sessionId, principal, channelIdentity, runtimePolicy, workingDirectory);
        registerBackend(scope);
        return scope;
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
                .build();
    }

    private void registerBackend(RuntimeScope scope) {
        backendFactory.registerSession(scope.getSessionId(),
                buildBackendConfig(scope.getRuntimePolicy(), scope.getWorkingDirectory().toString()));
        backendFactory.touchSession(scope.getSessionId());
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
        if (!runtimePolicy.getCapabilityView().getVisibleToolsets().contains(Toolset.CORE)) return null;
        SceneConfig.BackendSpec spec = runtimePolicy.getBackendSpec();
        boolean persistFs = spec != null ? spec.isDockerPersistFilesystem()
                : backendProperties.getDocker().isPersistFilesystem();
        if (!persistFs) return null;
        String root = backendProperties.getDocker().getWorkspaceRoot();
        String containerSuffix = sessionId.replace("-", "").substring(0, Math.min(8, sessionId.replace("-", "").length()));
        return Path.of(root, "husky-" + containerSuffix);
    }

    private BackendConfig buildBackendConfig(RuntimePolicy runtimePolicy, String workDir) {
        SceneConfig.BackendSpec spec = runtimePolicy.getBackendSpec();
        ExecutionBackendProperties.DockerProperties docker = backendProperties.getDocker();
        ExecutionBackendProperties.SshProperties ssh = backendProperties.getSsh();

        return switch (runtimePolicy.getBackendPolicy()) {
            case DOCKER -> BackendConfig.builder()
                    .type("docker")
                    .initialWorkDir(spec != null && spec.getDockerWorkdir() != null ? spec.getDockerWorkdir() : "/workspace")
                    .dockerImage(spec != null && spec.getDockerImage() != null ? spec.getDockerImage() : docker.getImage())
                    .dockerMemory(spec != null && spec.getDockerMemory() != null ? spec.getDockerMemory() : docker.getMemory())
                    .dockerCpus(spec != null && spec.getDockerCpus() != null ? spec.getDockerCpus() : docker.getCpus())
                    .dockerPersistFilesystem(spec != null ? spec.isDockerPersistFilesystem() : docker.isPersistFilesystem())
                    .dockerWorkspaceRoot(docker.getWorkspaceRoot())
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

    /** 校验 session 是否属于指定 principal */
    public boolean isSessionOwnedBy(String sessionId, String principalId) {
        return sessionRepository.isSessionOwnedBy(sessionId, principalId);
    }
}