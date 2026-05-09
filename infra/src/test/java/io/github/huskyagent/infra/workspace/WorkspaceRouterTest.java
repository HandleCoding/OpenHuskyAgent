package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRouterTest {

    @AfterEach
    void clearScope() {
        SessionContext.clear();
    }

    @Test
    void routesToLocalWhenScopeMissing() {
        Workspace local = new StubWorkspace("local");
        WorkspaceRouter router = new WorkspaceRouter(List.of(provider("local", local)));

        assertEquals(Path.of("local:file.txt"), router.resolve("file.txt"));
    }

    @Test
    void routesByWorkspaceTypeFromSessionScope() {
        Workspace local = new StubWorkspace("local");
        Workspace remote = new StubWorkspace("remote");
        WorkspaceRouter router = new WorkspaceRouter(List.of(provider("local", local), provider("s3", remote)));
        SessionContext.setScope(SessionScope.builder().workspaceType(" S3 ").build());

        assertEquals(Path.of("remote:file.txt"), router.resolve("file.txt"));
    }

    @Test
    void throwsWhenWorkspaceTypeHasNoProvider() {
        WorkspaceRouter router = new WorkspaceRouter(List.of(provider("local", new StubWorkspace("local"))));
        SessionContext.setScope(SessionScope.builder().workspaceType("s3").build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve("file.txt"));
        assertTrue(error.getMessage().contains("Unsupported workspace type: s3"));
    }

    @Test
    void rejectsDuplicateProviderTypes() {
        assertThrows(IllegalStateException.class, () -> new WorkspaceRouter(List.of(
                provider("local", new StubWorkspace("one")),
                provider(" LOCAL ", new StubWorkspace("two")))));
    }

    private WorkspaceProvider provider(String type, Workspace workspace) {
        return new WorkspaceProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Workspace workspace(SessionScope scope) {
                return workspace;
            }
        };
    }

    private record StubWorkspace(String name) implements Workspace {
        @Override
        public Path resolve(String path) {
            return Path.of(name + ":" + path);
        }

        @Override
        public Path root() {
            return Path.of(name);
        }

        @Override
        public boolean exists(Path path) {
            return false;
        }

        @Override
        public boolean isDirectory(Path path) {
            return false;
        }

        @Override
        public boolean isRegularFile(Path path) {
            return false;
        }

        @Override
        public boolean isSymbolicLink(Path path) {
            return false;
        }

        @Override
        public long size(Path path) {
            return 0;
        }

        @Override
        public String readString(Path path) {
            return "";
        }

        @Override
        public InputStream newInputStream(Path path) throws IOException {
            return InputStream.nullInputStream();
        }

        @Override
        public List<String> readAllLines(Path path) {
            return List.of();
        }

        @Override
        public void createDirectories(Path dir) {
        }

        @Override
        public void writeString(Path path, String content) {
        }

        @Override
        public void delete(Path path) {
        }

        @Override
        public boolean deleteIfExists(Path path) {
            return false;
        }

        @Override
        public void move(Path source, Path target) {
        }

        @Override
        public List<Path> walkFiles(Path root) {
            return List.of();
        }

        @Override
        public long getLastModifiedTime(Path path) {
            return 0;
        }

        @Override
        public Path toRealPath(Path path) {
            return path;
        }
    }
}
