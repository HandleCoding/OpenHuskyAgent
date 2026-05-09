package io.github.huskyagent.infra.execute;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOCAL execution backend — thin ProcessBuilder wrapper, one instance per session.
 * Behavior is identical to the original TerminalTool process spawning,
 * but process state is now scoped to the session rather than shared globally.
 */
@Slf4j
public class LocalBackend implements ExecutionBackend {

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;

    private final BackendConfig config;
    private final Map<String, ProcessInfo> processes = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private volatile boolean released = false;

    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    public LocalBackend(BackendConfig config) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
            2, Runtime.getRuntime().availableProcessors() * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "local-backend-" + threadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Override
    public ExecResult execute(String command, String cwd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = buildProcessBuilder(command, resolveCwd(cwd));
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            Future<Void> drainer = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                return null;
            });

            try {
                drainer.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                drainer.cancel(true);
                return new ExecResult("Command timeout after " + timeoutSeconds + "s", 124, false);
            }

            process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            return new ExecResult(output.toString(), exitCode, exitCode == 0);

        } catch (Exception e) {
            return new ExecResult("Execution failed: " + e.getMessage(), 1, false);
        }
    }

    @Override
    public String startBackground(String command, String cwd) {
        String taskId = UUID.randomUUID().toString();
        try {
            Process process = buildProcessBuilder(command, resolveCwd(cwd)).start();
            ProcessInfo info = new ProcessInfo(taskId, process, command, DEFAULT_MAX_OUTPUT_CHARS);
            processes.put(taskId, info);
            executor.submit(() -> monitorProcess(taskId));
            return taskId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to start background process: " + e.getMessage(), e);
        }
    }

    @Override
    public BackgroundStatus pollBackground(String taskId) {
        ProcessInfo info = processes.get(taskId);
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
        ProcessInfo info = processes.get(taskId);
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
        ProcessInfo info = processes.get(taskId);
        if (info == null) return new ExecResult("Task not found: " + taskId, 1, false);

        try {
            boolean finished = info.process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                return new ExecResult("Wait timeout after " + timeoutSeconds + "s", 124, false);
            }
            info.exitCode = info.process.exitValue();
            return new ExecResult(info.getOutput(), info.exitCode, info.exitCode == 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult("Wait interrupted", 1, false);
        }
    }

    @Override
    public void killBackground(String taskId) {
        ProcessInfo info = processes.remove(taskId);
        if (info != null) {
            info.process.destroyForcibly();
        }
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
        released = true;
        processes.values().forEach(info -> info.process.destroyForcibly());
        processes.clear();
        executor.shutdownNow();
    }

    @Override
    public boolean isAlive() {
        return !released;
    }

    private ProcessBuilder buildProcessBuilder(String command, String dir) {
        ProcessBuilder pb = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/bash", "-c", command);
        }
        pb.directory(new java.io.File(dir));
        pb.redirectErrorStream(true);
        return pb;
    }

    private String resolveCwd(String cwd) {
        if (cwd != null && !cwd.isBlank()) {
            return cwd.startsWith("~") ? cwd.replace("~", System.getProperty("user.home")) : cwd;
        }
        if (config.getInitialWorkDir() != null && !config.getInitialWorkDir().isBlank()) {
            return config.getInitialWorkDir();
        }
        return System.getProperty("user.dir");
    }

    private void monitorProcess(String taskId) {
        ProcessInfo info = processes.get(taskId);
        if (info == null) return;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(info.process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                info.appendOutput(line + "\n");
            }
            info.process.waitFor();
            info.exitCode = info.process.exitValue();
            log.debug("Background task {} completed with exit code {}", taskId, info.exitCode);
        } catch (Exception e) {
            log.error("Background task {} monitor error: {}", taskId, e.getMessage());
        }
    }

    static class ProcessInfo {
        final String taskId;
        final Process process;
        final String command;
        final int maxOutputChars;
        private final StringBuilder outputBuf = new StringBuilder();
        volatile int exitCode = -1;

        ProcessInfo(String taskId, Process process, String command, int maxOutputChars) {
            this.taskId = taskId;
            this.process = process;
            this.command = command;
            this.maxOutputChars = maxOutputChars;
        }

        synchronized void appendOutput(String chunk) {
            outputBuf.append(chunk);
            if (outputBuf.length() > maxOutputChars) {
                outputBuf.delete(0, outputBuf.length() - maxOutputChars);
            }
        }

        synchronized String getOutput() {
            return outputBuf.toString();
        }
    }
}
