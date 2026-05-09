package io.github.huskyagent.infra.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class HuskyDataPaths {

    private final Path rootDirectory;

    public HuskyDataPaths(@Value("${husky.data.dir:${HUSKY_DATA_DIR:${user.home}/.husky}}") String dataDir) {
        this.rootDirectory = expandHome(dataDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(dbDirectory());
        Files.createDirectories(memoryDirectory());
        Files.createDirectories(skillsDirectory());
        Files.createDirectories(configDirectory());
        warnIfLegacyDataExists();
        log.info("Husky data directory: {}", rootDirectory);
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public Path dbDirectory() {
        return rootDirectory.resolve("db");
    }

    public Path memoryDirectory() {
        return rootDirectory.resolve("memory");
    }

    public Path skillsDirectory() {
        return rootDirectory.resolve("skills");
    }

    public Path configDirectory() {
        return rootDirectory.resolve("config");
    }

    private Path expandHome(String value) {
        if (value == null || value.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".husky");
        }
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Path.of(value);
    }

    private void warnIfLegacyDataExists() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.equals(rootDirectory)) {
            return;
        }
        boolean legacyDb = Files.exists(cwd.resolve("data").resolve("sessions.db"));
        boolean legacyMemory = Files.exists(cwd.resolve(".hermes").resolve("memory").resolve("MEMORY.md"))
                || Files.exists(cwd.resolve(".hermes").resolve("memory").resolve("USER.md"));
        if (legacyDb || legacyMemory) {
            log.warn("Detected legacy runtime data under {}; Husky now uses {} by default", cwd, rootDirectory);
        }
    }
}
