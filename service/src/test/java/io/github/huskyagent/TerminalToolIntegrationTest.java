package io.github.huskyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalToolIntegrationTest extends AbstractIntegrationTest {

    @Test
    @Order(1)
    void testTerminalToolExecution() throws Exception {
        System.out.println("\n📋 Test: terminal tool execution");

        var result = toolExecutor.execute("terminal", Map.of(
            "command", "echo Hello Terminal"
        ));

        assertTrue(result.success(), "Terminal should succeed: " + result.error());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String stdout = (String) response.get("stdout");
        int exitCode = (Integer) response.get("exitCode");

        assertTrue(stdout.contains("Hello Terminal"), "Output should contain expected text");
        assertEquals(0, exitCode, "Exit code should be 0");

        System.out.println("✅ Terminal execution succeeded:");
        System.out.println("   Output: " + stdout.trim());
        System.out.println("   Exit code: " + exitCode);
    }

    @Test
    @Order(2)
    void testTerminalWithWorkingDir() throws Exception {
        System.out.println("\n📋 Test: terminal working directory");

        var result = toolExecutor.execute("terminal", Map.of(
            "command", "pwd",
            "workdir", tempDir.toString()
        ));

        assertTrue(result.success(), "Terminal with workdir should succeed");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String stdout = (String) response.get("stdout");

        assertTrue(stdout.contains(tempDir.toString()), "Output should show workdir");

        System.out.println("✅ Terminal working directory succeeded:");
        System.out.println("   Working directory: " + tempDir);
        System.out.println("   pwd Output: " + stdout.trim());
    }

    @Test
    @Order(3)
    void testTerminalBackgroundExecution() throws Exception {
        System.out.println("\n📋 Test: terminal background execution");

        var result = toolExecutor.execute("terminal", Map.of(
            "command", "sleep 2 && echo done",
            "background", true
        ));

        assertTrue(result.success(), "Background execution should succeed");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String taskId = (String) response.get("taskId");

        assertNotNull(taskId, "Should have taskId");

        System.out.println("✅ Background execution started:");
        System.out.println("   Task ID: " + taskId);

        toolExecutor.cancelTask(taskId);
    }

    @Test
    @Order(4)
    void testDangerousCommandBlocked() {
        System.out.println("\n📋 Test: dangerous command approval detection");

        var terminalDef = toolRegistry.get("terminal");
        assertNotNull(terminalDef, "terminal tool should be registered");
        assertTrue(terminalDef.requiresApproval(), "terminal should have approvalChecker");

        var dangerousRequest = terminalDef.checkApproval(Map.of("command", "rm -rf /tmp/test"));
        assertNotNull(dangerousRequest, "rm command should trigger approval request");
        System.out.println("✅ dangerous command detected:");
        System.out.println("   Command: rm -rf /tmp/test");
        System.out.println("   Reason: " + dangerousRequest.reason());

        var safeRequest = terminalDef.checkApproval(Map.of("command", "ls -la"));
        assertNull(safeRequest, "ls command should NOT trigger approval request");
        System.out.println("✅ safe command allowed:");
        System.out.println("   Command: ls -la → approval not required");
    }
}
