package io.github.huskyagent.application.session;

import io.github.huskyagent.application.runtime.RuntimeBackendCapabilityResolver;
import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.execute.BackendConfig;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.session.SessionRepository;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SessionResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void dockerPersistentScopeSharesHostWorkspaceWithBackendConfig() {
        AgentDefinition.BackendSpec spec = new AgentDefinition.BackendSpec();
        spec.setDockerPersistFilesystem(true);

        TestFixture fixture = fixture(spec, false);

        RuntimeScope scope = fixture.resolver().createEphemeralScope(principal(), identity(), "assistant");

        BackendConfig config = captureRegisteredConfig(fixture.backendFactory(), scope.getSessionId());
        Path expectedHostWorkspace = expectedHostWorkspace(scope.getSessionId());
        assertEquals(expectedHostWorkspace, scope.getWorkingDirectory());
        assertEquals(expectedHostWorkspace.toString(), config.getDockerHostWorkspaceDir());
        assertEquals("/workspace", config.getInitialWorkDir());
        assertTrue(config.isDockerPersistFilesystem());
    }

    @Test
    void dockerPersistFallsBackToGlobalWhenAgentSpecOmitsOverride() {
        AgentDefinition.BackendSpec spec = new AgentDefinition.BackendSpec();
        spec.setDockerImage("node:22");

        TestFixture fixture = fixture(spec, true);

        RuntimeScope scope = fixture.resolver().createEphemeralScope(principal(), identity(), "assistant");

        BackendConfig config = captureRegisteredConfig(fixture.backendFactory(), scope.getSessionId());
        assertTrue(config.isDockerPersistFilesystem());
        assertEquals(expectedHostWorkspace(scope.getSessionId()), scope.getWorkingDirectory());
        assertEquals(scope.getWorkingDirectory().toString(), config.getDockerHostWorkspaceDir());
        assertEquals("node:22", config.getDockerImage());
    }

    @Test
    void dockerPersistAgentFalseOverridesGlobalTrue() {
        AgentDefinition.BackendSpec spec = new AgentDefinition.BackendSpec();
        spec.setDockerPersistFilesystem(false);

        TestFixture fixture = fixture(spec, true);

        RuntimeScope scope = fixture.resolver().createEphemeralScope(principal(), identity(), "assistant");

        BackendConfig config = captureRegisteredConfig(fixture.backendFactory(), scope.getSessionId());
        assertFalse(config.isDockerPersistFilesystem());
        assertNull(config.getDockerHostWorkspaceDir());
        assertEquals(Path.of(System.getProperty("user.dir")), scope.getWorkingDirectory());
        assertFalse(scope.getFilesystemAvailable());
    }

    private TestFixture fixture(AgentDefinition.BackendSpec spec, boolean globalPersistFilesystem) {
        AgentDefinition scene = new AgentDefinition();
        scene.setAgentId("assistant");
        scene.setBackendPolicy(AgentDefinition.BackendPolicy.DOCKER);
        scene.setBackendSpec(spec);

        RuntimePolicy policy = RuntimePolicy.builder()
                .agentId("assistant")
                .backendPolicy(AgentDefinition.BackendPolicy.DOCKER)
                .backendSpec(spec)
                .build();

        AgentResolver agentResolver = mock(AgentResolver.class);
        when(agentResolver.resolve("assistant")).thenReturn(scene);
        RuntimePolicyResolver runtimePolicyResolver = mock(RuntimePolicyResolver.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllEnabled()).thenReturn(List.of());
        when(runtimePolicyResolver.resolve(eq(scene), eq(List.of()))).thenReturn(policy);

        ExecutionBackendFactory backendFactory = mock(ExecutionBackendFactory.class);
        ExecutionBackendProperties properties = new ExecutionBackendProperties();
        properties.getDocker().setWorkspaceRoot(tempDir.toString());
        properties.getDocker().setPersistFilesystem(globalPersistFilesystem);

        SessionResolver resolver = new SessionResolver(
                mock(SessionManager.class),
                mock(SessionRepository.class),
                agentResolver,
                runtimePolicyResolver,
                toolRegistry,
                backendFactory,
                properties,
                new RuntimeBackendCapabilityResolver(properties),
                scope -> "session-key",
                allowAllAccessPolicy());
        return new TestFixture(resolver, backendFactory);
    }

    private BackendConfig captureRegisteredConfig(ExecutionBackendFactory backendFactory, String sessionId) {
        ArgumentCaptor<BackendConfig> captor = ArgumentCaptor.forClass(BackendConfig.class);
        verify(backendFactory).registerSession(eq(sessionId), captor.capture());
        return captor.getValue();
    }

    private Path expectedHostWorkspace(String sessionId) {
        String normalized = sessionId.replace("-", "");
        return tempDir.resolve("husky-" + normalized.substring(0, Math.min(8, normalized.length())));
    }

    private Principal principal() {
        return Principal.builder()
                .id("user-1")
                .channelType(ChannelType.TUI)
                .build();
    }

    private ChannelIdentity identity() {
        return ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .build();
    }

    private SessionAccessPolicy allowAllAccessPolicy() {
        return new SessionAccessPolicy() {
            @Override
            public boolean canResume(IsolationScope current, io.github.huskyagent.infra.session.SessionEntity existing) {
                return true;
            }

            @Override
            public boolean canList(IsolationScope current, io.github.huskyagent.infra.session.SessionEntity existing) {
                return true;
            }

            @Override
            public boolean canSearchMemory(IsolationScope current, io.github.huskyagent.infra.session.SessionEntity existing) {
                return true;
            }
        };
    }

    private record TestFixture(SessionResolver resolver, ExecutionBackendFactory backendFactory) {}
}
