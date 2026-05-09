package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.infra.execute.BackendConfig;
import io.github.huskyagent.infra.execute.DockerBackend;
import io.github.huskyagent.infra.execute.ExecutionBackend;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.LocalBackend;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalTool implements ToolProvider {

    private static final List<DangerousPattern> DANGEROUS_PATTERNS = List.of(
        new DangerousPattern(Pattern.compile("\\brm\\s+(-[^\\s]*\\s+)*(-r|-R|--recursive)"),
            "rm -r: recursively delete files"),
        new DangerousPattern(Pattern.compile("\\brm\\s+(-[^\\s]*\\s+)*(-f|--force)"),
            "rm -f: forcibly delete files"),
        new DangerousPattern(Pattern.compile("\\brm\\s+(-[^\\s]*\\s+)*/"),
            "rm /: delete content under the root path"),
        new DangerousPattern(Pattern.compile("\\bchmod\\s+(-R\\s+)?[0-7]*7[0-7]{2}\\b"),
            "chmod: grant world-writable permissions"),
        new DangerousPattern(Pattern.compile("\\bchmod\\s+(-R\\s+)?777\\b"),
            "chmod 777: grant all permissions"),
        new DangerousPattern(Pattern.compile("\\bchown\\b"),
            "chown: change file ownership"),
        new DangerousPattern(Pattern.compile("\\bdd\\b"),
            "dd: directly read or write disk devices"),
        new DangerousPattern(Pattern.compile("\\bmkfs\\b"),
            "mkfs: format a filesystem"),
        new DangerousPattern(Pattern.compile("\\bfdisk\\b|\\bparted\\b|\\bdiskutil\\b"),
            "disk partitioning tool"),
        new DangerousPattern(Pattern.compile("\\bshutdown\\b|\\breboot\\b|\\bhalt\\b|\\bpoweroff\\b"),
            "system shutdown or reboot command"),
        new DangerousPattern(Pattern.compile("\\binit\\s+[0-6]\\b"),
            "init: switch runlevel"),
        new DangerousPattern(Pattern.compile("\\bsystemctl\\s+(stop|disable|mask|kill)\\b"),
            "systemctl: stop or disable system services"),
        new DangerousPattern(Pattern.compile("\\bservice\\s+\\S+\\s+(stop|restart)\\b"),
            "service: stop or restart services"),
        new DangerousPattern(Pattern.compile("\\biptables\\b|\\bufw\\s+(disable|reset)\\b"),
            "firewall rule modification"),
        new DangerousPattern(Pattern.compile("\\bsudo\\b"),
            "sudo: execute as root"),
        new DangerousPattern(Pattern.compile("\\bsu\\s+-\\b|\\bsu\\s+root\\b"),
            "su: switch to root"),
        new DangerousPattern(Pattern.compile("(curl|wget|fetch)\\b.*\\|\\s*(bash|sh|zsh|python|perl|ruby)\\b"),
            "remote script execution: curl/wget piped to shell"),
        new DangerousPattern(Pattern.compile(">\\s*/etc/"),
            "redirect overwrite of system configuration files under /etc/"),
        new DangerousPattern(Pattern.compile(">\\s*/boot/"),
            "redirect overwrite of boot files under /boot/"),
        new DangerousPattern(Pattern.compile("\\bgit\\s+(push\\s+.*--force|push\\s+.*-f)\\b"),
            "git push --force: forcibly overwrite remote branches"),
        new DangerousPattern(Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
            "git reset --hard: discard local changes"),
        new DangerousPattern(Pattern.compile("\\bgit\\s+clean\\s+(-[^\\s]*f|-f)"),
            "git clean -f: delete untracked files"),
        new DangerousPattern(Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
            "Fork bomb: process bomb"),
        new DangerousPattern(Pattern.compile("\\btruncate\\s+.*-s\\s*0\\b"),
            "truncate: empty file contents"),
        new DangerousPattern(Pattern.compile("\\bhistory\\s+-[cw]\\b"),
            "history -c/-w: clear command history")
    );

    private final ToolLimitsConfig limitsConfig;
    private final ExecutionBackendFactory backendFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LocalBackend ephemeralBackend = new LocalBackend(
        BackendConfig.builder().type("local").initialWorkDir(System.getProperty("user.dir")).build()
    );

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(createTerminalTool(), createProcessTool());
    }

    private ToolDefinition createTerminalTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        ObjectNode cmdNode = props.putObject("command");
        cmdNode.put("type", "string");
        cmdNode.put("description", "Shell command to execute");

        ObjectNode timeoutNode = props.putObject("timeout");
        timeoutNode.put("type", "integer");
        timeoutNode.put("description", "Timeout in seconds (default 120, max 600)");
        timeoutNode.put("default", limitsConfig.getTerminalDefaultTimeout());

        ObjectNode bgNode = props.putObject("background");
        bgNode.put("type", "boolean");
        bgNode.put("description", "Run in background");
        bgNode.put("default", false);

        ObjectNode cwdNode = props.putObject("workdir");
        cwdNode.put("type", "string");
        cwdNode.put("description", "Working directory");

        ArrayNode required = schema.putArray("required");
        required.add("command");

        return ToolDefinition.withApproval("terminal",
            "Execute shell commands. Use for builds, git, package managers. Do NOT use for file operations (use file tools instead).",
            Toolset.TERMINAL, schema, this::handleTerminal, this::checkTerminalApproval);
    }

    private ApprovalRequest checkTerminalApproval(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) return null;
        for (DangerousPattern dp : DANGEROUS_PATTERNS) {
            if (dp.pattern().matcher(command).find()) {
                String reason = "Dangerous operation: " + dp.reason() + ". Command: `" + command + "`";
                return ApprovalRequest.of(UUID.randomUUID().toString(), "terminal", args, reason, "default");
            }
        }
        return null;
    }

    ToolResult handleTerminal(Map<String, Object> args) {
        String command = (String) args.get("command");
        int timeout = args.containsKey("timeout")
            ? Math.min(((Number) args.get("timeout")).intValue(), limitsConfig.getTerminalMaxTimeout())
            : limitsConfig.getTerminalDefaultTimeout();
        boolean background = Boolean.TRUE.equals(args.get("background"));
        if (command == null || command.isEmpty()) {
            return ToolResult.failure("command required");
        }

        ExecutionBackend backend = resolveBackend(args);

        String workdir = args.containsKey("workdir") ? (String) args.get("workdir")
            : defaultWorkdirFor(backend);

        if (background) {
            String taskId = backend.startBackground(command, workdir);
            return ToolResult.success(Map.of(
                "taskId", taskId,
                "status", "running",
                "command", command
            ));
        }

        ExecutionBackend.ExecResult result = backend.execute(command, workdir, timeout);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stdout", truncateOutput(result.stdout()));
        response.put("exitCode", result.exitCode());
        response.put("success", result.success());
        if (!result.success()) {
            response.put("note", interpretExitCode(command, result.exitCode()));
        }
        return ToolResult.success(response);
    }

    private ToolDefinition createProcessTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        ObjectNode actionNode = props.putObject("action");
        actionNode.put("type", "string");
        actionNode.put("description", "Action: poll, log, wait, kill, list");
        ArrayNode actionEnum = actionNode.putArray("enum");
        actionEnum.add("poll").add("log").add("wait").add("kill").add("list");

        ObjectNode idNode = props.putObject("task_id");
        idNode.put("type", "string");
        idNode.put("description", "Background task ID");

        ObjectNode offsetNode = props.putObject("offset");
        offsetNode.put("type", "integer");
        offsetNode.put("description", "Line offset for log action (default 0)");
        offsetNode.put("default", 0);

        ObjectNode limitNode = props.putObject("limit");
        limitNode.put("type", "integer");
        limitNode.put("description", "Max lines to return for log action (default 100)");
        limitNode.put("default", 100);

        ObjectNode timeoutNode = props.putObject("timeout");
        timeoutNode.put("type", "integer");
        timeoutNode.put("description", "Timeout in seconds for wait action (default 300, max 600)");
        timeoutNode.put("default", limitsConfig.getTerminalDefaultWaitTimeout());

        ArrayNode required = schema.putArray("required");
        required.add("action");

        return ToolDefinition.of("process",
            "Manage background processes. Actions: poll (status + output preview), log (paginated output by line), wait (block until done), kill (terminate), list (show all).",
            Toolset.TERMINAL, schema, this::handleProcess);
    }

    ToolResult handleProcess(Map<String, Object> args) {
        String action = (String) args.get("action");
        String taskId = (String) args.get("task_id");

        if (action == null) return ToolResult.failure("action required");

        ExecutionBackend backend = resolveBackend(args);

        return switch (action) {
            case "poll" -> {
                if (taskId == null) yield ToolResult.failure("task_id required");
                ExecutionBackend.BackgroundStatus status = backend.pollBackground(taskId);
                if (status == null) yield ToolResult.failure("Task not found: " + taskId);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("taskId", status.taskId());
                r.put("command", status.command());
                r.put("running", status.running());
                r.put("outputPreview", status.outputPreview());
                r.put("totalChars", status.totalChars());
                if (!status.running()) r.put("exitCode", status.exitCode());
                yield ToolResult.success(r);
            }
            case "log" -> {
                if (taskId == null) yield ToolResult.failure("task_id required");
                int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
                int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 100;
                ExecutionBackend.BackgroundLog bl = backend.logBackground(taskId, offset, limit);
                if (bl == null) yield ToolResult.failure("Task not found: " + taskId);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("taskId", bl.taskId());
                r.put("running", bl.running());
                r.put("offset", bl.offset());
                r.put("returned", bl.returned());
                r.put("totalLines", bl.totalLines());
                r.put("hasMore", bl.hasMore());
                r.put("content", bl.content());
                yield ToolResult.success(r);
            }
            case "wait" -> {
                if (taskId == null) yield ToolResult.failure("task_id required");
                int timeout = limitsConfig.getTerminalDefaultWaitTimeout();
                if (args.containsKey("timeout")) {
                    timeout = Math.min(((Number) args.get("timeout")).intValue(), limitsConfig.getTerminalMaxTimeout());
                }
                ExecutionBackend.ExecResult result = backend.waitBackground(taskId, timeout);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("taskId", taskId);
                r.put("exitCode", result.exitCode());
                r.put("success", result.success());
                r.put("output", result.stdout());
                yield ToolResult.success(r);
            }
            case "kill" -> {
                if (taskId == null) yield ToolResult.failure("task_id required");
                backend.killBackground(taskId);
                yield ToolResult.success(Map.of("killed", taskId));
            }
            case "list" -> {
                List<Map<String, Object>> list = backend.listBackground();
                yield ToolResult.success(Map.of("processes", list, "total", list.size()));
            }
            default -> ToolResult.failure("Unknown action: " + action);
        };
    }

    private ExecutionBackend resolveBackend(Map<String, Object> args) {
        String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
        if (sessionId == null) sessionId = SessionContext.get();
        if (sessionId == null) {
            return ephemeralBackend;
        }
        return backendFactory.getForSession(sessionId);
    }

    private String defaultWorkdirFor(ExecutionBackend backend) {
        if (backend instanceof DockerBackend) {
            // Containers define their own working directory contract.
            return null;
        }
        var scope = SessionContext.getScope();
        return scope != null && scope.getWorkingDirectory() != null
                ? scope.getWorkingDirectory()
                : System.getProperty("user.dir");
    }

    private String truncateOutput(String output) {
        int maxOutputChars = limitsConfig.getTerminalMaxOutputChars();
        int headChars = (int) (maxOutputChars * 0.4);
        if (output.length() <= maxOutputChars) return output;
        int tailStart = output.length() - (maxOutputChars - headChars);
        return output.substring(0, headChars)
            + "\n\n[... " + (output.length() - maxOutputChars) + " chars truncated ...]\n\n"
            + output.substring(tailStart);
    }

    private String interpretExitCode(String command, int exitCode) {
        String baseCmd = command.trim().split("\\s+")[0];
        return switch (exitCode) {
            case 1 -> switch (baseCmd) {
                case "grep", "rg"  -> "No matches found";
                case "diff"        -> "Files are different";
                case "test", "["   -> "Condition false";
                case "git"         -> "Git command failed";
                case "npm", "yarn" -> "Package manager error";
                case "make"        -> "Make target failed";
                case "curl"        -> "curl: unsupported protocol or connection failed";
                default -> "General error (exit 1)";
            };
            case 2 -> switch (baseCmd) {
                case "grep", "rg" -> "Error in regex pattern";
                case "curl"       -> "curl: initialization failed";
                case "git"        -> "Git usage error";
                default -> "Misuse of shell builtin (exit 2)";
            };
            case 126 -> "Permission denied or not executable";
            case 127 -> "Command not found: " + baseCmd;
            case 128 -> "Invalid exit argument";
            case 130 -> "Terminated by Ctrl+C (SIGINT)";
            case 137 -> "Killed (SIGKILL) — possibly OOM";
            case 139 -> "Segmentation fault (SIGSEGV)";
            case 143 -> "Terminated (SIGTERM)";
            default  -> "Exit code " + exitCode;
        };
    }

    private record DangerousPattern(Pattern pattern, String reason) {}
}
