package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.infra.execute.BackendConfig;
import io.github.huskyagent.infra.execute.ExecutionBackend;
import io.github.huskyagent.infra.execute.ExecutionBackendFactory;
import io.github.huskyagent.infra.execute.ExecutionBackendProperties;
import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.adapter.ToolRuntimeEnvironment;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TerminalToolTest {

    @TempDir
    Path tempDir;

    private TerminalTool terminalTool;
    private ExecutionBackend backend;

    @BeforeEach
    void setUp() {
        // Use a real LocalBackend so terminal commands actually run
        backend = new io.github.huskyagent.infra.execute.LocalBackend(
            BackendConfig.builder().type("local").initialWorkDir(tempDir.toString()).build()
        );

        ExecutionBackendFactory factory = mock(ExecutionBackendFactory.class);
        when(factory.getForSession(anyString())).thenReturn(backend);

        terminalTool = new TerminalTool(new ToolLimitsConfig(), factory);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clear();
        backend.release();
    }

    @Test
    void testExecuteSimpleCommand() {
        Map<String, Object> args = Map.of("command", "echo hello");

        ToolResult result = terminalTool.handleTerminal(args);
        assertTrue(result.success());
        String content = extractOutput(result);
        assertTrue(content.contains("hello"));
    }

    @Test
    void testExecuteCommandWithExitCode() {
        Map<String, Object> args = Map.of("command", "ls /");

        ToolResult result = terminalTool.handleTerminal(args);
        assertTrue(result.success());
        String content = extractOutput(result);
        assertTrue(content.contains("bin") || content.contains("etc") || content.contains("usr"));
    }

    @Test
    void testExecuteCommandInWorkdir() {
        Map<String, Object> args = Map.of(
            "command", "pwd",
            "workdir", tempDir.toString()
        );

        ToolResult result = terminalTool.handleTerminal(args);
        assertTrue(result.success());
        String content = extractOutput(result);
        assertTrue(content.contains(tempDir.toString()));
    }

    @Test
    void testDefaultWorkdirFromSessionScope() {
        SessionContext.setScope(SessionScope.builder()
                .sessionId("session-1")
                .workingDirectory(tempDir.toString())
                .build());

        ToolResult result = terminalTool.handleTerminal(Map.of("command", "pwd"));

        assertTrue(result.success());
        assertTrue(extractOutput(result).contains(tempDir.toString()));
    }

    @Test
    void terminalUsesExecutionBackendFromContextWhenAvailable() {
        ExecutionBackend contextBackend = mock(ExecutionBackend.class);
        when(contextBackend.execute(eq("echo from-context"), eq(tempDir.toString()), anyInt()))
                .thenReturn(new ExecutionBackend.ExecResult("from-context\n", 0, true));
        when(contextBackend.isAlive()).thenReturn(true);
        ToolRuntimeEnvironment environment = new ToolRuntimeEnvironment(
                "local",
                true,
                () -> null,
                () -> contextBackend);
        ToolExecutionContext context = new ToolExecutionContext(
                "session-ctx",
                SessionScope.builder()
                        .sessionId("session-ctx")
                        .workingDirectory(tempDir.toString())
                        .backendType("local")
                        .filesystemAvailable(true)
                        .build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                environment);

        ToolResult result = terminalTool.handleTerminal(Map.of("command", "echo from-context"), context);

        assertTrue(result.success());
        assertTrue(extractOutput(result).contains("from-context"));
        verify(contextBackend).execute(eq("echo from-context"), eq(tempDir.toString()), anyInt());
    }

    @Test
    void testExecuteFailingCommand() {
        Map<String, Object> args = Map.of("command", "ls /nonexistent_dir_12345");

        ToolResult result = terminalTool.handleTerminal(args);
        assertTrue(result.success());  // Tool executed, command failed
        String content = result.content();
        assertTrue(content.contains("exitCode"));
    }

    @Test
    void testExecuteMissingCommand() {
        Map<String, Object> args = Map.of();

        ToolResult result = terminalTool.handleTerminal(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("required"));
    }

    @Test
    void testBackgroundExecution() {
        Map<String, Object> args = Map.of(
            "command", "sleep 2 && echo done",
            "background", true
        );

        ToolResult result = terminalTool.handleTerminal(args);
        assertTrue(result.success());
        String content = result.content();
        assertTrue(content.contains("taskId"));
        assertTrue(content.contains("running"));
    }

    @Test
    void testProcessList() {
        Map<String, Object> startArgs = Map.of(
            "command", "sleep 5",
            "background", true
        );

        ToolResult startResult = terminalTool.handleTerminal(startArgs);
        assertTrue(startResult.success());

        Map<String, Object> listArgs = Map.of("action", "list");
        ToolResult listResult = terminalTool.handleProcess(listArgs);
        assertTrue(listResult.success());
        String content = listResult.content();
        assertTrue(content.contains("processes") || content.contains("total"));
    }

    @Test
    void testProcessPoll() {
        Map<String, Object> startArgs = Map.of(
            "command", "sleep 3",
            "background", true
        );

        ToolResult startResult = terminalTool.handleTerminal(startArgs);
        assertTrue(startResult.success());

        String taskId = extractTaskId(startResult);

        Map<String, Object> pollArgs = Map.of(
            "action", "poll",
            "task_id", taskId
        );

        ToolResult pollResult = terminalTool.handleProcess(pollArgs);
        assertTrue(pollResult.success());
        String content = pollResult.content();
        assertTrue(content.contains("running"));
    }

    @Test
    void testProcessKill() {
        Map<String, Object> startArgs = Map.of(
            "command", "sleep 10",
            "background", true
        );

        ToolResult startResult = terminalTool.handleTerminal(startArgs);
        assertTrue(startResult.success());

        String taskId = extractTaskId(startResult);

        Map<String, Object> killArgs = Map.of(
            "action", "kill",
            "task_id", taskId
        );

        ToolResult killResult = terminalTool.handleProcess(killArgs);
        assertTrue(killResult.success());
        assertTrue(killResult.content().contains("killed"));

        Map<String, Object> pollArgs = Map.of(
            "action", "poll",
            "task_id", taskId
        );

        ToolResult pollResult = terminalTool.handleProcess(pollArgs);
        assertFalse(pollResult.success());
        assertTrue(pollResult.error().contains("not found"));
    }

    @Test
    void testProcessUnknownAction() {
        Map<String, Object> args = Map.of("action", "unknown");

        ToolResult result = terminalTool.handleProcess(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("Unknown"));
    }

    @Test
    void testProcessMissingAction() {
        Map<String, Object> args = Map.of();

        ToolResult result = terminalTool.handleProcess(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("required"));
    }

    @Test
    void testDangerousCommandApprovalChecker() {
        Map<String, Object> args = Map.of("command", "rm -rf /tmp/test");

        ExecutionBackendFactory factory = mock(ExecutionBackendFactory.class);
        var tool = new TerminalTool(new ToolLimitsConfig(), factory);
        var toolDefs = tool.getTools();

        var terminalDef = toolDefs.stream()
            .filter(d -> "terminal".equals(d.name()))
            .findFirst()
            .orElse(null);

        assertNotNull(terminalDef, "terminal tool should be in getTools()");
        assertTrue(terminalDef.requiresApproval(), "terminal should require approval");

        var approvalRequest = terminalDef.checkApproval(args);
        assertNotNull(approvalRequest, "dangerous command should trigger approval request");
        assertTrue(approvalRequest.reason().toLowerCase().contains("danger") ||
                   approvalRequest.reason().toLowerCase().contains("rm"),
                   "reason should mention danger or rm");
    }

    private String extractOutput(ToolResult result) {
        if (result.content() == null) return "";
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = mapper.readValue(result.content(), Map.class);
            Object stdout = map.get("stdout");
            return stdout != null ? stdout.toString() : "";
        } catch (Exception e) {
            return result.content();
        }
    }

    private String extractTaskId(ToolResult result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = mapper.readValue(result.content(), Map.class);
            return (String) map.get("taskId");
        } catch (Exception e) {
            return "";
        }
    }
}
