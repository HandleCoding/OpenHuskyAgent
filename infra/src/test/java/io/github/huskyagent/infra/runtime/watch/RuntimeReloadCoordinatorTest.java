package io.github.huskyagent.infra.runtime.watch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeReloadCoordinatorTest {

    @Test
    void testSuccessfulReloadClearsRequestedCaches() {
        RecordingInvalidation invalidation = new RecordingInvalidation();
        RuntimeReloadCoordinator coordinator = new RuntimeReloadCoordinator(
                List.of(new StubHandler(
                        RuntimeResourceType.SKILL,
                        RuntimeReloadOutcome.success(RuntimeResourceType.SKILL, "ok", true, true)
                )),
                invalidation
        );

        coordinator.onPathsChanged(Map.of(RuntimeResourceType.SKILL, Set.of(Path.of("/tmp/skill/SKILL.md"))));

        assertEquals(1, invalidation.promptCacheClears);
        assertEquals(1, invalidation.graphCacheClears);
    }

    @Test
    void testFailedReloadDoesNotClearCaches() {
        RecordingInvalidation invalidation = new RecordingInvalidation();
        RuntimeReloadCoordinator coordinator = new RuntimeReloadCoordinator(
                List.of(new StubHandler(
                        RuntimeResourceType.MCP_CONFIG,
                        RuntimeReloadOutcome.failure(RuntimeResourceType.MCP_CONFIG, "boom")
                )),
                invalidation
        );

        coordinator.onPathsChanged(Map.of(RuntimeResourceType.MCP_CONFIG, Set.of(Path.of("/tmp/mcp-servers.json"))));

        assertEquals(0, invalidation.promptCacheClears);
        assertEquals(0, invalidation.graphCacheClears);
    }

    @Test
    void testMissingHandlerIsIgnored() {
        RecordingInvalidation invalidation = new RecordingInvalidation();
        RuntimeReloadCoordinator coordinator = new RuntimeReloadCoordinator(List.of(), invalidation);

        coordinator.onPathsChanged(Map.of(RuntimeResourceType.SKILL, Set.of(Path.of("/tmp/skill/SKILL.md"))));

        assertEquals(0, invalidation.promptCacheClears);
        assertEquals(0, invalidation.graphCacheClears);
        assertTrue(true);
    }

    private static final class StubHandler implements RuntimeResourceReloadHandler {
        private final RuntimeResourceType type;
        private final RuntimeReloadOutcome outcome;

        private StubHandler(RuntimeResourceType type, RuntimeReloadOutcome outcome) {
            this.type = type;
            this.outcome = outcome;
        }

        @Override
        public RuntimeResourceDescriptor descriptor() {
            return new RuntimeResourceDescriptor(type, Set.of(Path.of("/tmp")), true);
        }

        @Override
        public RuntimeReloadOutcome reload(Set<Path> changedPaths) {
            return outcome;
        }
    }

    private static final class RecordingInvalidation implements RuntimeReloadInvalidation {
        private int promptCacheClears;
        private int graphCacheClears;

        @Override
        public void clearPromptCache() {
            promptCacheClears++;
        }

        @Override
        public void clearGraphCache() {
            graphCacheClears++;
        }
    }
}
