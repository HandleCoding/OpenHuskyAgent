package io.github.huskyagent.infra.workspace;

import io.github.huskyagent.infra.tool.impl.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class LocalWorkspace implements Workspace {

    @Override
    public Path resolve(String path) {
        if (path.startsWith("~")) {
            return Path.of(path.replace("~", System.getProperty("user.home")));
        }
        return Path.of(path).toAbsolutePath();
    }

    @Override
    public Path root() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public boolean isSymbolicLink(Path path) {
        return Files.isSymbolicLink(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    @Override
    public String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public String probeContentType(Path path) throws IOException {
        return Files.probeContentType(path);
    }

    @Override
    public List<String> readAllLines(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public void writeString(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(path);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }

    @Override
    public void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public List<Path> walkFiles(Path root) throws IOException {
        return FileUtils.walkFilesList(root);
    }

    @Override
    public long getLastModifiedTime(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return path.toRealPath();
    }
}
