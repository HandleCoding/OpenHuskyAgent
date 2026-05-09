package io.github.huskyagent.domain.graph;

import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.Toolset;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 GraphUtils.requiresApproval 的分组逻辑
 */
class ParallelToolExecutionTest {

    private ToolDefinition safeTool;
    private ToolDefinition approvalToolAlways;
    private ToolDefinition approvalToolConditional;

    @BeforeEach
    void setUp() {
        // 安全工具：无 approvalChecker
        safeTool = ToolDefinition.of(
                "read_file", "读取文件", Toolset.CORE,
                JsonNodeFactory.instance.objectNode(),
                args -> null
        );

        // 审批工具：approvalChecker 总是返回 ApprovalRequest（危险命令）
        approvalToolAlways = ToolDefinition.withApproval(
                "terminal", "执行终端命令", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> new ApprovalRequest("req1", "terminal", args, "危险命令", "session1")
        );

        // 条件审批工具：approvalChecker 根据参数决定（command 包含 "rm" 时需要审批）
        approvalToolConditional = ToolDefinition.withApproval(
                "terminal_cond", "条件审批终端", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> {
                    String cmd = String.valueOf(args.getOrDefault("command", ""));
                    if (cmd.contains("rm")) {
                        return new ApprovalRequest("req2", "terminal_cond", args, "危险删除", "session1");
                    }
                    return null; // ls、cat 等安全命令无需审批
                }
        );
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, args);
    }

    // ── requiresApproval 测试 ──────────────────────────────────────────────────

    @Test
    void safeToolDoesNotRequireApproval() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("read_file", safeTool, "terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("read_file", "{}");
        assertFalse(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void approvalToolAlwaysRequiresApproval() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("terminal", "{\"command\":\"rm -rf /tmp\"}");
        assertTrue(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void conditionalApprovalToolRequiresApprovalForDangerousArgs() {
        Set<String> approvalNames = Set.of("terminal_cond");
        Map<String, ToolDefinition> defMap = Map.of("terminal_cond", approvalToolConditional);

        AssistantMessage.ToolCall dangerous = toolCall("terminal_cond", "{\"command\":\"rm -rf /tmp\"}");
        assertTrue(GraphUtils.requiresApproval(dangerous, approvalNames, defMap));
    }

    @Test
    void conditionalApprovalToolDoesNotRequireApprovalForSafeArgs() {
        Set<String> approvalNames = Set.of("terminal_cond");
        Map<String, ToolDefinition> defMap = Map.of("terminal_cond", approvalToolConditional);

        AssistantMessage.ToolCall safe = toolCall("terminal_cond", "{\"command\":\"ls -la\"}");
        assertFalse(GraphUtils.requiresApproval(safe, approvalNames, defMap));
    }

    @Test
    void toolNotInApprovalNamesIsAlwaysSafe() {
        // 工具名不在 approvalNames 集合中，即使 ToolDefinition 有 approvalChecker 也不需要审批
        Set<String> approvalNames = Set.of(); // 空集合
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("terminal", "{\"command\":\"rm -rf /\"}");
        assertFalse(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void malformedArgsDefaultsToRequiresApproval() {
        // 参数 JSON 格式错误时，保守判断为需要审批
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("terminal", "not-valid-json");
        assertTrue(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void toolDefinitionNotInMapDefaultsToRequiresApproval() {
        // approvalNames 包含该工具但 defMap 中没有对应定义，保守判断为需要审批
        Set<String> approvalNames = Set.of("unknown_tool");
        Map<String, ToolDefinition> defMap = Map.of(); // 空

        AssistantMessage.ToolCall call = toolCall("unknown_tool", "{}");
        assertTrue(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    // ── 分组逻辑验证（通过 requiresApproval 模拟 parallel_executor 的分组） ───

    @Test
    void groupingMixedToolCalls() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of(
                "read_file", safeTool,
                "terminal", approvalToolAlways
        );

        List<AssistantMessage.ToolCall> calls = List.of(
                toolCall("read_file", "{}"),
                toolCall("terminal", "{\"command\":\"rm -rf /tmp\"}"),
                toolCall("read_file", "{\"path\":\"/etc/hosts\"}")
        );

        long safeCount = calls.stream()
                .filter(c -> !GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();
        long approvalCount = calls.stream()
                .filter(c -> GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();

        assertEquals(2, safeCount,   "应有 2 个安全工具");
        assertEquals(1, approvalCount, "应有 1 个审批工具");
    }

    @Test
    void allSafeToolsGroupedCorrectly() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("read_file", safeTool);

        List<AssistantMessage.ToolCall> calls = List.of(
                toolCall("read_file", "{}"),
                toolCall("read_file", "{\"path\":\"/tmp/a.txt\"}")
        );

        long approvalCount = calls.stream()
                .filter(c -> GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();
        assertEquals(0, approvalCount, "全是安全工具，审批数应为 0");
    }

    @Test
    void allApprovalToolsGroupedCorrectly() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        List<AssistantMessage.ToolCall> calls = List.of(
                toolCall("terminal", "{\"command\":\"rm -rf /tmp\"}"),
                toolCall("terminal", "{\"command\":\"chmod 777 /etc\"}")
        );

        long approvalCount = calls.stream()
                .filter(c -> GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();
        assertEquals(2, approvalCount, "全是审批工具，审批数应为 2");
    }
}
