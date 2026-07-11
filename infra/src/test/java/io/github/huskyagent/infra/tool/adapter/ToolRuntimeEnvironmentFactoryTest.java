package io.github.huskyagent.infra.tool.adapter;

import io.github.huskyagent.infra.execute.BackendConfig;
import io.github.huskyagent.infra.execute.ExecutionBackend;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.execute.LocalBackend;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.workspace.LocalWorkspace;
import io.github.huskyagent.infra.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRuntimeEnvironmentFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void localEnvironmentHasFilesystemAndLazyBackend() {
        CountingBackendFactory backendFactory = new CountingBackendFactory();
        ToolRuntimeEnvironmentFactory factory = new ToolRuntimeEnvironmentFactory(
                backendFactory,
                List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
        SessionScope scope = SessionScope.builder()
                .sessionId("session-1")
                .backendType("local")
                .filesystemAvailable(true)
                .workspaceType("local")
                .workingDirectory(tempDir.toString())
                .build();

        ToolRuntimeEnvironment environment = factory.create(scope);

        assertEquals("local", environment.backendType());
        assertTrue(environment.isLocalBackend());
        assertTrue(environment.hasFilesystem());
        assertEquals(0, backendFactory.calls);
        assertEquals(tempDir.resolve("x.txt").normalize(), environment.workspace().resolve("x.txt"));
        assertEquals(0, backendFactory.calls);

        assertNotNull(environment.executionBackend());
        assertEquals(1, backendFactory.calls);
    }

    @Test
    void sshEnvironmentDisablesFilesystem() {
        ToolRuntimeEnvironmentFactory factory = new ToolRuntimeEnvironmentFactory(
                new CountingBackendFactory(),
                List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
        SessionScope scope = SessionScope.builder()
                .sessionId("session-1")
                .backendType("ssh")
                .filesystemAvailable(false)
                .workspaceType("local")
                .workingDirectory(tempDir.toString())
                .build();

        ToolRuntimeEnvironment environment = factory.create(scope);

        assertEquals("ssh", environment.backendType());
        assertFalse(environment.hasFilesystem());
        IllegalStateException error = assertThrows(IllegalStateException.class, environment::workspace);
        assertTrue(error.getMessage().contains("File tools are not available"));
    }

    @Test
    void dockerPersistentWorkspaceMapsContainerAbsolutePathsToHostRoot() {
        ToolRuntimeEnvironmentFactory factory = new ToolRuntimeEnvironmentFactory(
                new CountingBackendFactory(),
                List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
        SessionScope scope = SessionScope.builder()
                .sessionId("session-1")
                .backendType("docker")
                .filesystemAvailable(true)
                .workspaceType("local")
                .workingDirectory(tempDir.toString())
                .runtimeWorkingDirectory("/workspace")
                .build();

        ToolRuntimeEnvironment environment = factory.create(scope);

        assertEquals(tempDir.resolve("a.txt").normalize(), environment.workspace().resolve("a.txt"));
        assertEquals(tempDir.resolve("a.txt").normalize(), environment.workspace().resolve("/workspace/a.txt"));
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> environment.workspace().resolve("/etc/passwd"));
        assertTrue(error.getMessage().contains("outside runtime workspace"));
        IllegalStateException relativeEscape = assertThrows(
                IllegalStateException.class,
                () -> environment.workspace().resolve("../outside.txt"));
        assertTrue(relativeEscape.getMessage().contains("outside runtime workspace"));
    }

    @Test
    void inheritedEnvironmentCopiesBackendMetaAndUsesChildSession() {
        CountingBackendFactory backendFactory = new CountingBackendFactory();
        ToolRuntimeEnvironmentFactory factory = new ToolRuntimeEnvironmentFactory(
                backendFactory,
                List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
        ToolExecutionContext parentContext = ToolExecutionContext.scoped(
                SessionScope.builder().sessionId("parent").backendType("docker").build(),
                List.of(),
                new ToolRuntimeEnvironment("docker", false, () -> null, () -> null));
        SessionScope childScope = SessionScope.builder()
                .sessionId("child")
                .backendType("docker")
                .filesystemAvailable(false)
                .workspaceType("local")
                .build();

        ToolRuntimeEnvironment environment = factory.inherit(parentContext, childScope);

        assertEquals("parent", backendFactory.inheritedParent);
        assertEquals("child", backendFactory.inheritedChild);
        environment.executionBackend();
        assertEquals("child", backendFactory.lastSessionId);
    }

    @Test
    void nonLocalExecutionBackendFailsClosedWhenSessionMetaIsMissing() {
        ToolRuntimeEnvironmentFactory factory = new ToolRuntimeEnvironmentFactory(
                new ExecutionBackendFactory(new ExecutionBackendProperties()),
                List.of(new LocalWorkspaceProvider(new LocalWorkspace())));
        SessionScope scope = SessionScope.builder()
                .sessionId("unregistered-docker")
                .backendType("docker")
                .filesystemAvailable(false)
                .workspaceType("local")
                .build();

        ToolRuntimeEnvironment environment = factory.create(scope);

        IllegalStateException error = assertThrows(IllegalStateException.class, environment::executionBackend);
        assertTrue(error.getMessage().contains("No backend metadata registered"));
    }

    private static class CountingBackendFactory extends ExecutionBackendFactory {
        int calls;
        String lastSessionId;
        String inheritedParent;
        String inheritedChild;
        private final ExecutionBackend backend = new LocalBackend(
                BackendConfig.builder().type("local").initialWorkDir(System.getProperty("user.dir")).build());

        CountingBackendFactory() {
            super(new ExecutionBackendProperties());
        }

        @Override
        public ExecutionBackend getForSession(String sessionId) {
            calls++;
            lastSessionId = sessionId;
            return backend;
        }

        @Override
        public ExecutionBackend getForSession(String sessionId, String expectedBackendType) {
            calls++;
            lastSessionId = sessionId;
            return backend;
        }

        @Override
        public void inheritSession(String parentSessionId, String childSessionId) {
            inheritedParent = parentSessionId;
            inheritedChild = childSessionId;
        }
    }
}
