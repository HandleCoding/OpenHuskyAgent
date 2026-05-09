package io.github.huskyagent.domain.graph.util;

import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AgentGraph 图节点/边的公共静态工具方法。
 */
@Slf4j
public final class GraphUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path PROMPT_DEBUG_DIR = Path.of(".husky", "debug");
    private static final Path SYSTEM_PROMPT_FILE = PROMPT_DEBUG_DIR.resolve("system-prompt.txt");
    private static final Path MESSAGE_LIST_FILE = PROMPT_DEBUG_DIR.resolve("message-list.txt");

    private GraphUtils() {}

    // ── 审批判断 ──────────────────────────────────────────────────────────────

    /**
     * 判断一个工具调用是否需要审批。
     * 先检查静态 approvalToolNames 集合，再委托工具自身的 approvalChecker 动态判断。
     * JSON 解析失败时保守返回 true（需要审批）。
     */
    public static boolean requiresApproval(AssistantMessage.ToolCall call,
                                           Set<String> approvalToolNames,
                                           Map<String, ToolDefinition> toolDefinitionMap) {
        if (!approvalToolNames.contains(call.name())) return false;
        ToolDefinition def = toolDefinitionMap.get(call.name());
        if (def == null) return true;
        try {
            Map<String, Object> args = MAPPER.readValue(call.arguments(), MAP_TYPE);
            return def.checkApproval(args) != null;
        } catch (Exception e) {
            log.warn("[GraphUtils] 解析工具参数失败，保守判断为需要审批: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 从工具定义列表中收集所有需要审批的工具名。
     */
    public static Set<String> collectApprovalToolNames(List<ToolDefinition> definitions) {
        Set<String> names = new HashSet<>();
        for (ToolDefinition def : definitions) {
            if (def.requiresApproval()) names.add(def.name());
        }
        return names;
    }

    // ── 工具执行 ──────────────────────────────────────────────────────────────

    /**
     * 同步执行单个工具调用，返回 ToolResponse（供并发 Future 使用）。
     */
    public static ToolResponseMessage.ToolResponse executeSingleToolCall(
            org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService toolService,
            AssistantMessage.ToolCall call,
            Map<String, Object> stateData) {
        ToolCallback cb = toolService.agentFunction(call.name())
                .orElseThrow(() -> new IllegalStateException("工具未注册: " + call.name()));
        String result = cb.call(call.arguments(), new ToolContext(stateData));
        log.debug("[parallel_executor] 工具 {} 执行完成", call.name());
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(), result);
    }

    public static Map<String, Object> parseArguments(String arguments) {
        try {
            return MAPPER.readValue(arguments, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 从工具执行结果的 state update 中提取 ToolResponseMessage 的 content 字符串。
     * 用于判断工具是否返回了错误响应（含 "error":true）。
     */
    @SuppressWarnings("unchecked")
    public static String extractToolResponseContent(Map<String, Object> update) {
        Object messages = update.get("messages");
        if (messages instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining(" "));
        }
        if (messages instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ToolResponseMessage trm) {
                    return trm.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::responseData)
                            .collect(Collectors.joining(" "));
                }
            }
        }
        return null;
    }

    /**
     * 构造工具被拒绝时的 ToolResponseMessage，让 LLM 感知拒绝原因。
     */
    public static ToolResponseMessage buildRejectionMessage(AssistantMessage.ToolCall denied) {
        String responseData = "工具 '%s' 的执行已被用户拒绝！".formatted(denied.name());
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        denied.id(), denied.name(), responseData)))
                .build();
    }

    /**
     * 构造指定内容的 ToolResponseMessage（供 Hook 阻塞等场景使用）。
     */
    public static ToolResponseMessage buildToolResponseMessage(String toolCallId,
                                                                String toolName,
                                                                String responseData) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        toolCallId, toolName, responseData)))
                .build();
    }

    // ── 日志 ──────────────────────────────────────────────────────────────────

    public static void logLlmRequest(String systemPrompt, List<Message> messages) {
        String systemPromptText = safeText(systemPrompt);
        String messageListText = formatMessages(messages);
        log.info("[model] 调用 LLM，systemPromptChars={}, messageCount={}",
                systemPromptText.length(), messages.size());
        writePromptDebugFiles(systemPromptText, messageListText);
    }

    public static void logLlmResponse(AssistantMessage output, String finishReason) {
        log.info("[model] LLM 响应：hasToolCalls={}, toolCount={}, finishReason={}, text={}",
                output.hasToolCalls(),
                output.hasToolCalls() ? output.getToolCalls().size() : 0,
                finishReason,
                truncate(output.getText(), 100));
        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(tc ->
                    log.info("[model]   tool_call: name={}, args={}", tc.name(), truncate(tc.arguments(), 120)));
        }
    }

    // ── 字符串工具 ────────────────────────────────────────────────────────────

    private static String safeText(String s) {
        return s == null || s.isBlank() ? "<empty>" : s;
    }

    private static void writePromptDebugFiles(String systemPrompt, String messageList) {
        try {
            Files.createDirectories(PROMPT_DEBUG_DIR);
            Files.writeString(SYSTEM_PROMPT_FILE, systemPrompt, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(MESSAGE_LIST_FILE, messageList, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.warn("[model] 写入 prompt debug 文件失败: {}", e.getMessage());
        }
    }

    private static String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            sb.append("--- message[").append(i).append("] type=").append(m.getMessageType()).append(" ---\n");
            sb.append(safeText(m.getText())).append('\n');
            if (m instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                assistantMessage.getToolCalls().forEach(toolCall -> sb.append("tool_call name=")
                        .append(toolCall.name())
                        .append(" args=")
                        .append(toolCall.arguments())
                        .append('\n'));
            }
            if (m instanceof ToolResponseMessage toolResponseMessage) {
                toolResponseMessage.getResponses().forEach(response -> sb.append("tool_response name=")
                        .append(response.name())
                        .append(" id=")
                        .append(response.id())
                        .append(" data=")
                        .append(response.responseData())
                        .append('\n'));
            }
        }
        return sb.toString();
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
