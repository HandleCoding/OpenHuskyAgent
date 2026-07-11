package io.github.huskyagent.infra.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public class ScopedWorkspace implements Workspace {

    private final Workspace delegate;
    private final Path root;
    private final Path runtimeRoot;

    public ScopedWorkspace(Workspace delegate, Path root) {
        this(delegate, root, null);
    }

    public ScopedWorkspace(Workspace delegate, Path root, Path runtimeRoot) {
        this.delegate = delegate;
        this.root = root.toAbsolutePath().normalize();
        this.runtimeRoot = runtimeRoot != null ? runtimeRoot.toAbsolutePath().normalize() : null;
    }

    @Override
    public Path resolve(String path) {
        Path raw = Path.of(path);
        if (runtimeRoot != null) {
            if (path.startsWith("~")) {
                throw new IllegalStateException("Path is outside runtime workspace: " + path);
            }
            Path normalized = raw.toAbsolutePath().normalize();
            if (raw.isAbsolute()) {
                if (!normalized.startsWith(runtimeRoot)) {
                    throw new IllegalStateException("Path is outside runtime workspace: " + path);
                }
                return root.resolve(runtimeRoot.relativize(normalized)).toAbsolutePath().normalize();
            }
        }
        if (path.startsWith("~") || raw.isAbsolute()) {
            return delegate.resolve(path);
        }
        Path resolved = root.resolve(raw).toAbsolutePath().normalize();
        if (runtimeRoot != null && !resolved.startsWith(root)) {
            throw new IllegalStateException("Path is outside runtime workspace: " + path);
        }
        return resolved;
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public boolean exists(Path path) {
        return delegate.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return delegate.isDirectory(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return delegate.isRegularFile(path);
    }

    @Override
    public boolean isSymbolicLink(Path path) {
        return delegate.isSymbolicLink(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return delegate.size(path);
    }

    @Override
    public String readString(Path path) throws IOException {
        return delegate.readString(path);
    }

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return delegate.newInputStream(path);
    }

    @Override
    public String probeContentType(Path path) throws IOException {
        return delegate.probeContentType(path);
    }

    @Override
    public List<String> readAllLines(Path path) throws IOException {
        return delegate.readAllLines(path);
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
        delegate.createDirectories(dir);
    }

    @Override
    public void writeString(Path path, String content) throws IOException {
        delegate.writeString(path, content);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(path);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return delegate.deleteIfExists(path);
    }

    @Override
    public void move(Path source, Path target) throws IOException {
        delegate.move(source, target);
    }

    @Override
    public List<Path> walkFiles(Path root) throws IOException {
        return delegate.walkFiles(root);
    }

    @Override
    public long getLastModifiedTime(Path path) throws IOException {
        return delegate.getLastModifiedTime(path);
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return delegate.toRealPath(path);
    }
}
