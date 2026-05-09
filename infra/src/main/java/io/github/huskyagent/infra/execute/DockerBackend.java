package io.github.huskyagent.infra.execute;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

/**
 * DOCKER execution backend — one container per session.
 *
 * <p>Container is started with {@code docker run -d ... sleep infinity} on construction.
 * Commands execute via {@code docker exec <containerId> bash -c ...}.
 * Uses only ProcessBuilder + docker CLI, no Docker Java SDK required.</p>
 *
 * Security flags (from Hermes reference):
 *   --cap-drop ALL, --cap-add DAC_OVERRIDE/CHOWN/FOWNER
 *   --security-opt no-new-privileges, --pids-limit 256
 *   --tmpfs /tmp:rw,nosuid,size=512m
 */
@Slf4j
public class DockerBackend implements ExecutionBackend {

    private static final List<String> SECURITY_ARGS = List.of(
        "--cap-drop", "ALL",
        "--cap-add", "DAC_OVERRIDE",
        "--cap-add", "CHOWN",
        "--cap-add", "FOWNER",
        "--security-opt", "no-new-privileges",
        "--pids-limit", "256",
        "--tmpfs", "/tmp:rw,nosuid,size=512m"
    );

    private final BackendConfig config;
    private final String containerName;
    private final String hostWorkspaceDir;  // host-side bind-mount directory
    private volatile String containerId;
    private volatile boolean released = false;
    private final Map<String, LocalBackend.ProcessInfo> processes = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    // Persistent cwd across commands, mirrors Hermes _cwd_file pattern
    private volatile String currentCwd;

    public DockerBackend(BackendConfig config) {
        this.config = config;
        this.containerName = "husky-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String root = config.getDockerWorkspaceRoot() != null
                ? config.getDockerWorkspaceRoot() : "/tmp/husky-sandbox";
        this.hostWorkspaceDir = root + "/" + containerName;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "docker-backend");
            t.setDaemon(true);
            return t;
        });
        startContainer();
        this.currentCwd = config.getInitialWorkDir();
    }

    private void startContainer() {
        String workDir = config.getInitialWorkDir() != null ? config.getInitialWorkDir() : "/workspace";

        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", containerName));
        cmd.addAll(SECURITY_ARGS);

        if (config.getDockerCpus() != null && !config.getDockerCpus().isBlank()) {
            cmd.addAll(List.of("--cpus", config.getDockerCpus()));
        }
        if (config.getDockerMemory() != null && !config.getDockerMemory().isBlank()) {
            cmd.addAll(List.of("--memory", config.getDockerMemory()));
        }

        // Always bind-mount a host directory — avoids in-memory tmpfs consuming RAM.
        // persist-filesystem only controls whether the directory is deleted on release.
        new java.io.File(hostWorkspaceDir).mkdirs();
        cmd.addAll(List.of("-v", hostWorkspaceDir + ":" + workDir));

        cmd.addAll(List.of("-w", workDir, config.getDockerImage(), "sleep", "infinity"));

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            containerId = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit != 0 || containerId.isBlank()) {
                throw new RuntimeException("docker run failed (exit=" + exit + "): " + containerId);
            }
            log.info("Started Docker container: name={}, id={}", containerName, shortId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Docker container: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecResult execute(String command, String cwd, int timeoutSeconds) {
        // Caller-supplied cwd takes priority; fall back to persisted currentCwd
        String effectiveCwd = (cwd != null && !cwd.isBlank()) ? cwd : currentCwd;
        // Append pwd capture so we can persist the cwd after cd commands
        String wrapped = "cd " + shellQuote(effectiveCwd) + " && " + command + "; echo \"__CWD__=$(pwd)\"";
        List<String> cmd = List.of("docker", "exec", containerId, "bash", "-c", wrapped);

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();

            Future<Void> drainer = executor.submit(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) output.append(line).append("\n");
                }
                return null;
            });

            try {
                drainer.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                p.destroyForcibly();
                return new ExecResult("Command timeout after " + timeoutSeconds + "s", 124, false);
            }

            p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int exitCode = p.exitValue();

            // Extract and strip the __CWD__ marker, update currentCwd
            String raw = output.toString();
            String cleaned = extractAndUpdateCwd(raw);

            return new ExecResult(cleaned, exitCode, exitCode == 0);

        } catch (Exception e) {
            return new ExecResult("docker exec failed: " + e.getMessage(), 1, false);
        }
    }

    private String extractAndUpdateCwd(String output) {
        int markerIdx = output.lastIndexOf("__CWD__=");
        if (markerIdx < 0) return output;
        int lineStart = output.lastIndexOf('\n', markerIdx - 1);
        String cwdLine = output.substring(markerIdx + "__CWD__=".length()).trim();
        // cwdLine may have trailing newline
        int newline = cwdLine.indexOf('\n');
        if (newline >= 0) cwdLine = cwdLine.substring(0, newline).trim();
        if (!cwdLine.isBlank()) {
            currentCwd = cwdLine;
        }
        // Strip the __CWD__ line from output
        return lineStart >= 0 ? output.substring(0, lineStart + 1) : output.substring(0, markerIdx);
    }

    @Override
    public String startBackground(String command, String cwd) {
        String effectiveCwd = (cwd != null && !cwd.isBlank()) ? cwd : currentCwd;
        String wrapped = "cd " + shellQuote(effectiveCwd) + " && " + command;
        List<String> dockerCmd = List.of("docker", "exec", containerId, "bash", "-c", wrapped);
        String taskId = UUID.randomUUID().toString();

        try {
            Process p = new ProcessBuilder(dockerCmd).redirectErrorStream(true).start();
            LocalBackend.ProcessInfo info = new LocalBackend.ProcessInfo(taskId, p, command, 200_000);
            processes.put(taskId, info);
            executor.submit(() -> monitorProcess(taskId));
            return taskId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to start background process in container: " + e.getMessage(), e);
        }
    }

    @Override
    public BackgroundStatus pollBackground(String taskId) {
        LocalBackend.ProcessInfo info = processes.get(taskId);
        if (info == null) return null;

        String fullOutput = info.getOutput();
        String preview = fullOutput.length() > 1000
            ? "...[truncated]\n" + fullOutput.substring(fullOutput.length() - 1000)
            : fullOutput;

        return new BackgroundStatus(
            taskId, info.command, info.process.isAlive(),
            preview, fullOutput.length(),
            info.process.isAlive() ? null : info.exitCode
        );
    }

    @Override
    public BackgroundLog logBackground(String taskId, int offset, int limit) {
        LocalBackend.ProcessInfo info = processes.get(taskId);
        if (info == null) return null;

        String[] lines = info.getOutput().split("\n", -1);
        int totalLines = lines.length;
        int from = Math.min(offset, totalLines);
        int to = Math.min(from + limit, totalLines);
        String page = String.join("\n", Arrays.copyOfRange(lines, from, to));

        return new BackgroundLog(taskId, info.process.isAlive(), from, to - from, totalLines, to < totalLines, page);
    }

    @Override
    public ExecResult waitBackground(String taskId, int timeoutSeconds) {
        LocalBackend.ProcessInfo info = processes.get(taskId);
        if (info == null) return new ExecResult("Task not found: " + taskId, 1, false);

        try {
            boolean finished = info.process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) return new ExecResult("Wait timeout after " + timeoutSeconds + "s", 124, false);
            info.exitCode = info.process.exitValue();
            return new ExecResult(info.getOutput(), info.exitCode, info.exitCode == 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult("Wait interrupted", 1, false);
        }
    }

    @Override
    public void killBackground(String taskId) {
        LocalBackend.ProcessInfo info = processes.remove(taskId);
        if (info != null) info.process.destroyForcibly();
    }

    @Override
    public List<Map<String, Object>> listBackground() {
        List<Map<String, Object>> list = new ArrayList<>();
        processes.forEach((id, info) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("taskId", id);
            p.put("command", info.command);
            p.put("running", info.process.isAlive());
            if (!info.process.isAlive()) p.put("exitCode", info.exitCode);
            list.add(p);
        });
        return list;
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        processes.values().forEach(info -> info.process.destroyForcibly());
        processes.clear();
        executor.shutdownNow();

        if (containerId != null) {
            String id = containerId;
            boolean persist = config.isDockerPersistFilesystem();
            String hostDir = hostWorkspaceDir;
            Thread cleanup = new Thread(() -> {
                try {
                    new ProcessBuilder("docker", "rm", "-f", id)
                        .redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS);
                    log.info("Removed Docker container: {}", id.substring(0, Math.min(12, id.length())));
                } catch (Exception e) {
                    log.warn("Error removing container {}: {}", id, e.getMessage());
                }
                // Delete host workspace directory unless persist-filesystem=true
                if (!persist && hostDir != null) {
                    try {
                        deleteDirectory(new java.io.File(hostDir));
                        log.info("Deleted workspace directory: {}", hostDir);
                    } catch (Exception e) {
                        log.warn("Error deleting workspace {}: {}", hostDir, e.getMessage());
                    }
                }
            }, "docker-cleanup-" + containerName);
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    @Override
    public boolean isAlive() {
        if (released || containerId == null) return false;
        try {
            Process p = new ProcessBuilder(
                "docker", "inspect", "--format", "{{.State.Running}}", containerId
            ).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return "true".equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    private void monitorProcess(String taskId) {
        LocalBackend.ProcessInfo info = processes.get(taskId);
        if (info == null) return;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(info.process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                info.appendOutput(line + "\n");
            }
            info.process.waitFor();
            info.exitCode = info.process.exitValue();
            log.debug("Docker background task {} completed with exit code {}", taskId, info.exitCode);
        } catch (Exception e) {
            log.error("Docker background task {} monitor error: {}", taskId, e.getMessage());
        }
    }

    private void deleteDirectory(java.io.File dir) {
        if (!dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String shortId() {
        return containerId != null ? containerId.substring(0, Math.min(12, containerId.length())) : "null";
    }
}
