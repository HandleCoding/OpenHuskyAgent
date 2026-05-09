package io.github.huskyagent;

import io.github.huskyagent.domain.graph.util.GraphUtils;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并行工具执行集成测试
 *
 * 验证真实注册的工具集在 parallel_executor 分组逻辑下的行为：
 * - 安全工具（read_file、list_files 等）应分入安全组
 * - 危险工具（terminal 执行 rm 命令等）应分入审批组
 */
class ParallelToolIntegrationTest extends AbstractIntegrationTest {

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("id-" + name, "function", name, args);
    }

    /** 从 ToolRegistry 构建 approvalToolNames 和 toolDefinitionMap（与 AgentGraph 逻辑一致） */
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
        assertFalse(tools.isEmpty(), "应有已注册的工具");
        assertTrue(tools.size() >= 5, "至少应有 5 个工具");
    }

    @Test
    void fileToolsAreClassifiedAsSafe() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        // 文件工具应为安全工具（无 approvalChecker）
        List<String> fileToolNames = List.of("read_file", "list_files", "search_files");
        for (String name : fileToolNames) {
            if (!defMap.containsKey(name)) continue; // 工具未注册则跳过
            AssistantMessage.ToolCall call = toolCall(name, "{}");
            assertFalse(GraphUtils.requiresApproval(call, approvalNames, defMap),
                    name + " 应为安全工具，不需要审批");
        }
    }

    @Test
    void terminalWithDangerousCommandRequiresApproval() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        if (!defMap.containsKey("terminal")) return; // terminal 未注册则跳过

        // rm -rf 应触发审批
        AssistantMessage.ToolCall dangerousCall = toolCall("terminal",
                "{\"command\":\"rm -rf /tmp/test\"}");
        assertTrue(GraphUtils.requiresApproval(dangerousCall, approvalNames, defMap),
                "terminal rm -rf 应需要审批");
    }

    @Test
    void terminalWithSafeCommandDoesNotRequireApproval() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        if (!defMap.containsKey("terminal")) return; // terminal 未注册则跳过

        // ls 命令应为安全
        AssistantMessage.ToolCall safeCall = toolCall("terminal",
                "{\"command\":\"ls -la /tmp\"}");
        assertFalse(GraphUtils.requiresApproval(safeCall, approvalNames, defMap),
                "terminal ls 不应需要审批");
    }

    @Test
    void mixedToolCallsGroupCorrectly() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        // 构造混合工具调用列表（只使用实际已注册的工具）
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

        if (calls.size() < 2) return; // 工具不足则跳过

        long safeCount = calls.stream()
                .filter(c -> !GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();
        long approvalCount = calls.stream()
                .filter(c -> GraphUtils.requiresApproval(c, approvalNames, defMap))
                .count();

        assertTrue(safeCount >= 1, "混合列表中应有至少 1 个安全工具");
        if (defMap.containsKey("terminal")) {
            assertTrue(approvalCount >= 1, "混合列表中应有至少 1 个需审批工具（terminal rm）");
        }
    }

    @Test
    void approvalToolNamesMatchToolsWithApprovalChecker() {
        Set<String> approvalNames = buildApprovalToolNames();
        Map<String, ToolDefinition> defMap = buildToolDefinitionMap();

        // approvalNames 应与 requiresApproval() 返回 true 的工具集一致
        for (ToolDefinition def : toolRegistry.getAllEnabled()) {
            boolean hasChecker = def.requiresApproval();
            boolean inApprovalNames = approvalNames.contains(def.name());
            assertEquals(hasChecker, inApprovalNames,
                    "工具 " + def.name() + " 的 requiresApproval() 与 approvalNames 集合应一致");
        }
    }
}
