package io.github.huskyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalToolIntegrationTest extends AbstractIntegrationTest {

    @Test
    @Order(1)
    void testTerminalToolExecution() throws Exception {
        System.out.println("\n📋 测试: terminal 工具执行");

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

        System.out.println("✅ 终端执行成功:");
        System.out.println("   输出: " + stdout.trim());
        System.out.println("   退出码: " + exitCode);
    }

    @Test
    @Order(2)
    void testTerminalWithWorkingDir() throws Exception {
        System.out.println("\n📋 测试: terminal 指定工作目录");

        var result = toolExecutor.execute("terminal", Map.of(
            "command", "pwd",
            "workdir", tempDir.toString()
        ));

        assertTrue(result.success(), "Terminal with workdir should succeed");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String stdout = (String) response.get("stdout");

        assertTrue(stdout.contains(tempDir.toString()), "Output should show workdir");

        System.out.println("✅ 终端指定目录成功:");
        System.out.println("   工作目录: " + tempDir);
        System.out.println("   pwd 输出: " + stdout.trim());
    }

    @Test
    @Order(3)
    void testTerminalBackgroundExecution() throws Exception {
        System.out.println("\n📋 测试: terminal 后台执行");

        var result = toolExecutor.execute("terminal", Map.of(
            "command", "sleep 2 && echo done",
            "background", true
        ));

        assertTrue(result.success(), "Background execution should succeed");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String taskId = (String) response.get("taskId");

        assertNotNull(taskId, "Should have taskId");

        System.out.println("✅ 后台执行启动成功:");
        System.out.println("   Task ID: " + taskId);

        toolExecutor.cancelTask(taskId);
    }

    @Test
    @Order(4)
    void testDangerousCommandBlocked() {
        System.out.println("\n📋 测试: 危险命令审批检测");

        var terminalDef = toolRegistry.get("terminal");
        assertNotNull(terminalDef, "terminal tool should be registered");
        assertTrue(terminalDef.requiresApproval(), "terminal should have approvalChecker");

        var dangerousRequest = terminalDef.checkApproval(Map.of("command", "rm -rf /tmp/test"));
        assertNotNull(dangerousRequest, "rm command should trigger approval request");
        System.out.println("✅ 危险命令识别:");
        System.out.println("   命令: rm -rf /tmp/test");
        System.out.println("   原因: " + dangerousRequest.reason());

        var safeRequest = terminalDef.checkApproval(Map.of("command", "ls -la"));
        assertNull(safeRequest, "ls command should NOT trigger approval request");
        System.out.println("✅ 安全命令放行:");
        System.out.println("   命令: ls -la → 无需审批");
    }
}
