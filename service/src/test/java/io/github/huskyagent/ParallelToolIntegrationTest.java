package io.github.huskyagent;

import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ParallelToolIntegrationTest extends AbstractIntegrationTest {

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, args);
    }

    private Set<String> buildApprovalToolNames() {
        return toolRegistry.getAllEnabled().stream()
                .filter(ToolDefinition::requiresApproval)
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
    }

    private Map<String, ToolDefinition> buildToolDefinitionMap() {
        Map<String, ToolDefinition> map = new HashMap<>();
        for (ToolDefinition def : toolRegistry.getAllEnabled()) {
            map.put(def.name(), def);
        }
        return map;
    }

    @Test
    void registeredToolsExist() {
        List<ToolDefinition> tools = toolRegistry.getAllEnabled();
        assertFalse(tools.isEmpty(), "should have registered tools");
        assertTrue(tools.size() >= 5, "should have at least 5 tools");
    }

    @Test
    void fileToolsAreClassifiedAsSafe() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        List<String> fileToolNames = List.of("read_file", "list_files", "search_files");
        for (String name : fileToolNames) {
            if (!defMap.containsKey(name)) continue;
            AssistantMessage.ToolCall call = toolCall(name, "{}");
            assertFalse(GraphUtils.requiresApproval(call, approvalNames, defMap),
                    name + " should be safe and not require approval");
        }
    }

    @Test
    void terminalWithDangerousCommandRequiresApproval() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        if (!defMap.containsKey("terminal")) return;

        AssistantMessage.ToolCall dangerousCall = toolCall("terminal",
                "{\"command\":\"rm -rf /tmp/test\"}");
        assertTrue(GraphUtils.requiresApproval(dangerousCall, approvalNames, defMap),
                "terminal rm -rf should require approval");
    }

    @Test
    void terminalWithSafeCommandDoesNotRequireApproval() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        if (!defMap.containsKey("terminal")) return;

        AssistantMessage.ToolCall safeCall = toolCall("terminal",
                "{\"command\":\"ls -la /tmp\"}");
        assertFalse(GraphUtils.requiresApproval(safeCall, approvalNames, defMap),
                "terminal ls should not require approval");
    }

    @Test
    void mixedToolCallsGroupCorrectly() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        if (defMap.containsKey("read_file")) {
            calls.add(toolCall("read_file", "{\"path\":\"/tmp/test.txt\"}"));
        }
        if (defMap.containsKey("list_files")) {
            calls.add(toolCall("list_files", "{\"path\":\"/tmp\"}"));
        }
        if (defMap.containsKey("terminal")) {
            calls.add(toolCall("terminal", "{\"command\":\"rm -rf /tmp/dangerous\"}"));
        }

        if (calls.size() < 2) return;

        long safeCount = calls.stream()
                .filter(c -> !GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();
        long approvalCount = calls.stream()
                .filter(c -> GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();

        assertTrue(safeCount >= 1, "mixed list should contain at least one safe tool");
        if (defMap.containsKey("terminal")) {
            assertTrue(approvalCount >= 1, "mixed list should contain at least one approval-required tool (terminal rm)");
        }
    }

    @Test
    void approvalToolNamesMatchToolsWithApprovalChecker() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        for (ToolDefinition def : toolRegistry.getAllEnabled()) {
            boolean hasChecker = def.requiresApproval();
            boolean inApprovalNames = approvalNames.contains(def.name());
            assertEquals(hasChecker, inApprovalNames,
                    "tools " + def.name() + " requiresApproval() should match approvalNames");
        }
    }
}
