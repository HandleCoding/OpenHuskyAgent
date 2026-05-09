package io.github.huskyagent.infra.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface Workspace {

    Path resolve(String path);

    Path root();

    boolean exists(Path path);

    boolean isDirectory(Path path);

    boolean isRegularFile(Path path);

    boolean isSymbolicLink(Path path);

    long size(Path path) throws IOException;

    String readString(Path path) throws IOException;

    InputStream newInputStream(Path path) throws IOException;

    /**
     * Probe the content type for a path resolved by this workspace.
     */
    default String probeContentType(Path path) throws IOException {
        return null;
    }

    List<String> readAllLines(Path path) throws IOException;

    void createDirectories(Path dir) throws IOException;

    void writeString(Path path, String content) throws IOException;

    void delete(Path path) throws IOException;

    boolean deleteIfExists(Path path) throws IOException;

    void move(Path source, Path target) throws IOException;

    List<Path> walkFiles(Path root) throws IOException;

    long getLastModifiedTime(Path path) throws IOException;

    Path toRealPath(Path path) throws IOException;
}
