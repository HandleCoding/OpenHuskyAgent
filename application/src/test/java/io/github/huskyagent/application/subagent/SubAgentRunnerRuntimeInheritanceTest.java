package io.github.huskyagent.application.subagent;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.subagent.SubAgentMessageQueue;
import io.github.huskyagent.infra.config.SubAgentConfig;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.adapter.ToolRuntimeEnvironment;
import io.github.huskyagent.infra.tool.adapter.ToolRuntimeEnvironmentFactory;
import io.github.huskyagent.infra.workspace.LocalWorkspace;
import io.github.huskyagent.infra.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentRunnerRuntimeInheritanceTest {

    @Test
    void childSceneInheritsParentBackendForCapabilityFiltering() throws Exception {
        ToolExecutionContext parentContext = parentContext("ssh", false);
        SubAgentRunner runner = runner(parentContext);

        Method method = SubAgentRunner.class.getDeclaredMethod("buildAgentDefinition", String.class);
        method.setAccessible(true);
        AgentDefinition agentDefinition = (AgentDefinition) method.invoke(runner, "system");

        assertEquals(AgentDefinition.BackendPolicy.SSH, agentDefinition.getBackendPolicy());
    }

    @Test
    void childSceneInheritsParentDockerRuntimeWorkingDirectory() throws Exception {
        ToolExecutionContext parentContext = parentContext("docker", true, "/app");
        SubAgentRunner runner = runner(parentContext);

        Method method = SubAgentRunner.class.getDeclaredMethod("buildAgentDefinition", String.class);
        method.setAccessible(true);
        AgentDefinition agentDefinition = (AgentDefinition) method.invoke(runner, "system");

        assertEquals(AgentDefinition.BackendPolicy.DOCKER, agentDefinition.getBackendPolicy());
        assertNotNull(agentDefinition.getBackendSpec());
        assertEquals("/app", agentDefinition.getBackendSpec().getDockerWorkdir());
    }

    @Test
    void childToolRuntimeEnvironmentInheritsParentSessionThroughFactory() throws Exception {
        ToolRuntimeEnvironment parentEnvironment = new ToolRuntimeEnvironment(
                "docker",
                true,
                () -> null,
                () -> null);
        ToolRuntimeEnvironment childEnvironment = new ToolRuntimeEnvironment(
                "docker",
                true,
                () -> null,
                () -> null);
        ToolExecutionContext parentContext = new ToolExecutionContext(
                "parent-session",
                SessionScope.builder()
                        .sessionId("parent-session")
                        .backendType("docker")
                        .filesystemAvailable(true)
                        .workingDirectory("/tmp/parent")
                        .build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                parentEnvironment);
        RecordingRuntimeEnvironmentFactory factory = new RecordingRuntimeEnvironmentFactory(childEnvironment);
        SubAgentRunner runner = runner(parentContext, factory);

        Method method = SubAgentRunner.class.getDeclaredMethod("runtimeEnvironmentFor", SessionScope.class);
        method.setAccessible(true);
        ToolRuntimeEnvironment actual = (ToolRuntimeEnvironment) method.invoke(
                runner,
                SessionScope.builder().sessionId("child-session").backendType("local").build());

        assertSame(childEnvironment, actual);
        assertSame(parentContext, factory.parentContext);
        assertEquals("child-session", factory.childScope.getSessionId());
    }

    @Test
    void childRuntimeScopeInheritsParentFilesystemCapability() throws Exception {
        ToolExecutionContext parentContext = parentContext("docker", true, "/app");
        SubAgentRunner runner = runner(parentContext);
        RuntimePolicy policy = RuntimePolicy.builder()
                .agentId("subagent")
                .backendPolicy(AgentDefinition.BackendPolicy.DOCKER)
                .backendSpec(dockerSpec("/app", true))
                .memoryPolicy(memoryPolicy())
                .capabilityView(io.github.huskyagent.domain.capability.CapabilityView.builder().build())
                .knowledgeSources(Set.of())
                .build();

        Method method = SubAgentRunner.class.getDeclaredMethod(
                "buildChildRuntimeScope",
                String.class,
                AgentDefinition.class,
                RuntimePolicy.class);
        method.setAccessible(true);
        io.github.huskyagent.application.session.RuntimeScope scope =
                (io.github.huskyagent.application.session.RuntimeScope) method.invoke(
                        runner,
                        "child-session",
                        new AgentDefinition(),
                        policy);

        assertTrue(scope.getFilesystemAvailable());
        assertTrue(scope.toSessionScope().getFilesystemAvailable());
        assertEquals("/app", scope.toSessionScope().getRuntimeWorkingDirectory());
    }

    @Test
    void childRuntimeScopeKeepsParentFilesystemUnavailable() throws Exception {
        ToolExecutionContext parentContext = parentContext("ssh", false);
        SubAgentRunner runner = runner(parentContext);
        RuntimePolicy policy = RuntimePolicy.builder()
                .agentId("subagent")
                .backendPolicy(AgentDefinition.BackendPolicy.SSH)
                .memoryPolicy(memoryPolicy())
                .capabilityView(io.github.huskyagent.domain.capability.CapabilityView.builder().build())
                .knowledgeSources(Set.of())
                .build();

        Method method = SubAgentRunner.class.getDeclaredMethod(
                "buildChildRuntimeScope",
                String.class,
                AgentDefinition.class,
                RuntimePolicy.class);
        method.setAccessible(true);
        io.github.huskyagent.application.session.RuntimeScope scope =
                (io.github.huskyagent.application.session.RuntimeScope) method.invoke(
                        runner,
                        "child-session",
                        new AgentDefinition(),
                        policy);

        assertFalse(scope.getFilesystemAvailable());
        assertFalse(scope.toSessionScope().getFilesystemAvailable());
    }

    private ToolExecutionContext parentContext(String backendType, boolean filesystemAvailable) {
        return parentContext(backendType, filesystemAvailable, null);
    }

    private ToolExecutionContext parentContext(String backendType, boolean filesystemAvailable, String runtimeWorkingDirectory) {
        return new ToolExecutionContext(
                "parent-session",
                SessionScope.builder()
                        .sessionId("parent-session")
                        .backendType(backendType)
                        .filesystemAvailable(filesystemAvailable)
                        .workingDirectory("/tmp/parent")
                        .runtimeWorkingDirectory(runtimeWorkingDirectory)
                        .build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                new ToolRuntimeEnvironment(backendType, filesystemAvailable, () -> null, () -> null));
    }

    private AgentDefinition.BackendSpec dockerSpec(String runtimeWorkingDirectory, boolean persistFilesystem) {
        AgentDefinition.BackendSpec spec = new AgentDefinition.BackendSpec();
        spec.setDockerWorkdir(runtimeWorkingDirectory);
        spec.setDockerPersistFilesystem(persistFilesystem);
        return spec;
    }

    private MemoryPolicyConfig memoryPolicy() {
        return MemoryPolicyConfig.builder()
                .enabled(false)
                .strategyId("default")
                .access(AgentDefinition.MemoryAccess.DISABLED)
                .scope(AgentDefinition.MemoryScopePolicy.SESSION)
                .promptMode(AgentDefinition.MemoryPromptMode.NONE)
                .providers(Set.of())
                .build();
    }

    private SubAgentRunner runner(ToolExecutionContext parentContext) {
        return runner(parentContext, null);
    }

    private SubAgentRunner runner(ToolExecutionContext parentContext, ToolRuntimeEnvironmentFactory runtimeEnvironmentFactory) {
        return new SubAgentRunner(
                null,
                null,
                new SubAgentConfig(),
                new SubAgentTask("goal", "", Set.of(Toolset.CORE), 3, 30, Path.of("/tmp/parent"), 0),
                new SubAgentMessageQueue(),
                "parent-session",
                parentContext,
                null,
                null,
                null,
                null,
                runtimeEnvironmentFactory);
    }

    private static class RecordingRuntimeEnvironmentFactory extends ToolRuntimeEnvironmentFactory {
        private final ToolRuntimeEnvironment childEnvironment;
        private ToolExecutionContext parentContext;
        private SessionScope childScope;

        RecordingRuntimeEnvironmentFactory(ToolRuntimeEnvironment childEnvironment) {
            super(new ExecutionBackendFactory(new ExecutionBackendProperties()),
                    List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
            this.childEnvironment = childEnvironment;
        }

        @Override
        public ToolRuntimeEnvironment inherit(ToolExecutionContext parentContext, SessionScope childScope) {
            this.parentContext = parentContext;
            this.childScope = childScope;
            return childEnvironment;
        }
    }
}
