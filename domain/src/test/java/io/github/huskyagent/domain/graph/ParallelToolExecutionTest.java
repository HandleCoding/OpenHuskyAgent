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

class ParallelToolExecutionTest {

    private ToolDefinition safeTool;
    private ToolDefinition approvalToolAlways;
    private ToolDefinition approvalToolConditional;

    @BeforeEach
    void setUp() {
        safeTool = ToolDefinition.of(
                "read_file", "read file", Toolset.CORE,
                JsonNodeFactory.instance.objectNode(),
                args -> null
        );

        approvalToolAlways = ToolDefinition.withApproval(
                "terminal", "execute terminal command", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> new ApprovalRequest("req1", "terminal", args, "dangerous command", "session1")
        );

        approvalToolConditional = ToolDefinition.withApproval(
                "terminal_cond", "conditional approval terminal", Toolset.TERMINAL,
                JsonNodeFactory.instance.objectNode(),
                args -> null,
                args -> {
                    String cmd = String.valueOf(args.getOrDefault("command", ""));
                    if (cmd.contains("rm")) {
                        return new ApprovalRequest("req2", "terminal_cond", args, "dangerous delete", "session1");
                    }
                    return null;
                }
        );
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, args);
    }


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
        Set<String> approvalNames = Set.of();
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("terminal", "{\"command\":\"rm -rf /\"}");
        assertFalse(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void malformedArgsDefaultsToRequiresApproval() {
        Set<String> approvalNames = Set.of("terminal");
        Map<String, ToolDefinition> defMap = Map.of("terminal", approvalToolAlways);

        AssistantMessage.ToolCall call = toolCall("terminal", "not-valid-json");
        assertTrue(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }

    @Test
    void toolDefinitionNotInMapDefaultsToRequiresApproval() {
        Set<String> approvalNames = Set.of("unknown_tool");
        Map<String, ToolDefinition> defMap = Map.of();

        AssistantMessage.ToolCall call = toolCall("unknown_tool", "{}");
        assertTrue(GraphUtils.requiresApproval(call, approvalNames, defMap));
    }


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

        assertEquals(2, safeCount,   "should have 2 safe tools");
        assertEquals(1, approvalCount, "should have 1 approval tool");
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
        assertEquals(0, approvalCount, "all tools are safe, approval count should be 0");
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
        assertEquals(2, approvalCount, "all tools require approval, approval count should be 2");
    }
}
