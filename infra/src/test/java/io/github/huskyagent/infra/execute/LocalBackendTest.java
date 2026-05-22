package io.github.huskyagent.infra.execute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LocalBackendTest {

    @TempDir
    Path tempDir;

    @Test
    void foregroundCommandDoesNotDependOnBackgroundMonitorExecutorCapacity() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LocalBackend backend = new LocalBackend(
                BackendConfig.builder().type("local").initialWorkDir(tempDir.toString()).build(),
                executor);

        try {
            backend.startBackground("sleep 2", tempDir.toString());

            long startedAt = System.nanoTime();
            ExecutionBackend.ExecResult result = backend.execute("echo quick", tempDir.toString(), 1);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(result.success(), result.stdout());
            assertTrue(result.stdout().contains("quick"));
            assertTrue(elapsedMs < 1_000, "foreground command should not wait for background monitor; elapsed=" + elapsedMs);
        } finally {
            backend.release();
        }
    }

    @Test
    void foregroundCommandReturnsCompleteLargeOutput() {
        LocalBackend backend = new LocalBackend(
                BackendConfig.builder().type("local").initialWorkDir(tempDir.toString()).build());

        try {
            ExecutionBackend.ExecResult result = backend.execute("seq 1 5000", tempDir.toString(), 5);

            assertTrue(result.success(), result.stdout());
            assertTrue(result.stdout().startsWith("1\n2\n3\n"));
            assertTrue(result.stdout().contains("5000\n"));
        } finally {
            backend.release();
        }
    }

    @Test
    void timedOutCommandDoesNotContinueAfterReturn() throws Exception {
        LocalBackend backend = new LocalBackend(
                BackendConfig.builder().type("local").initialWorkDir(tempDir.toString()).build());
        Path marker = tempDir.resolve("marker.txt");

        try {
            ExecutionBackend.ExecResult result = backend.execute(
                    "sleep 5; touch " + marker.toAbsolutePath(),
                    tempDir.toString(),
                    1);

            assertFalse(result.success());
            assertEquals(124, result.exitCode());
            Thread.sleep(5000);
            assertFalse(Files.exists(marker), "timed-out command should not keep running after execute returns");
        } finally {
            backend.release();
        }
    }
}
