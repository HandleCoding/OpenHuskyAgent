package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WorkspaceRouter implements Workspace {

    private final Map<String, WorkspaceProvider> providers;

    public WorkspaceRouter(List<WorkspaceProvider> providers) {
        this.providers = buildProviderMap(providers);
    }

    private Workspace current() {
        SessionScope scope = SessionContext.getScope();
        String type = normalizeType(scope != null ? scope.getWorkspaceType() : null);
        WorkspaceProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported workspace type: " + type);
        }
        return provider.workspace(scope);
    }

    private Map<String, WorkspaceProvider> buildProviderMap(List<WorkspaceProvider> providers) {
        Map<String, WorkspaceProvider> result = new HashMap<>();
        for (WorkspaceProvider provider : providers) {
            String type = normalizeRequiredType(provider.type());
            if (result.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException("Duplicate workspace provider type: " + type);
            }
        }
        return Map.copyOf(result);
    }

    private String normalizeRequiredType(String type) {
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Workspace provider type is required");
        }
        return normalized;
    }

    private String normalizeType(String type) {
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isEmpty() ? "local" : normalized;
    }

    @Override
    public Path resolve(String path) {
        return current().resolve(path);
    }

    @Override
    public Path root() {
        return current().root();
    }

    @Override
    public boolean exists(Path path) {
        return current().exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return current().isDirectory(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return current().isRegularFile(path);
    }

    @Override
    public boolean isSymbolicLink(Path path) {
        return current().isSymbolicLink(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return current().size(path);
    }

    @Override
    public String readString(Path path) throws IOException {
        return current().readString(path);
    }

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return current().newInputStream(path);
    }

    @Override
    public String probeContentType(Path path) throws IOException {
        return current().probeContentType(path);
    }

    @Override
    public List<String> readAllLines(Path path) throws IOException {
        return current().readAllLines(path);
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
        current().createDirectories(dir);
    }

    @Override
    public void writeString(Path path, String content) throws IOException {
        current().writeString(path, content);
    }

    @Override
    public void delete(Path path) throws IOException {
        current().delete(path);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return current().deleteIfExists(path);
    }

    @Override
    public void move(Path source, Path target) throws IOException {
        current().move(source, target);
    }

    @Override
    public List<Path> walkFiles(Path root) throws IOException {
        return current().walkFiles(root);
    }

    @Override
    public long getLastModifiedTime(Path path) throws IOException {
        return current().getLastModifiedTime(path);
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return current().toRealPath(path);
    }
}
