package io.github.huskyagent.infra.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HuskyDataPathsTest {

    @Test
    void defaultsToUserHomeHuskyDirectory() {
        HuskyDataPaths paths = new HuskyDataPaths("");

        assertEquals(Path.of(System.getProperty("user.home"), ".husky").toAbsolutePath().normalize(),
                paths.rootDirectory());
    }

    @Test
    void expandsHomeShortcut() {
        HuskyDataPaths paths = new HuskyDataPaths("~/custom-husky");

        assertEquals(Path.of(System.getProperty("user.home"), "custom-husky").toAbsolutePath().normalize(),
                paths.rootDirectory());
    }

    @Test
    void resolvesStableSubDirectories() {
        HuskyDataPaths paths = new HuskyDataPaths("/tmp/husky-data");

        assertEquals(Path.of("/tmp/husky-data/db"), paths.dbDirectory());
        assertEquals(Path.of("/tmp/husky-data/memory"), paths.memoryDirectory());
        assertEquals(Path.of("/tmp/husky-data/skills"), paths.skillsDirectory());
        assertEquals(Path.of("/tmp/husky-data/config"), paths.configDirectory());
    }
}
