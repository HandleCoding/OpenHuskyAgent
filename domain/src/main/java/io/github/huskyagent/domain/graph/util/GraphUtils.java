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

@Slf4j
public final class GraphUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path PROMPT_DEBUG_DIR = Path.of(".husky", "debug");
    private static final Path SYSTEM_PROMPT_FILE = PROMPT_DEBUG_DIR.resolve("system-prompt.txt");
    private static final Path MESSAGE_LIST_FILE = PROMPT_DEBUG_DIR.resolve("message-list.txt");

    private GraphUtils() {}


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
            log.warn("[GraphUtils] Failed to parse tool arguments; conservatively requiring approval: {}", e.getMessage());
            return true;
        }
    }

    public static Set<String> collectApprovalToolNames(List<ToolDefinition> definitions) {
        Set<String> names = new HashSet<>();
        for (ToolDefinition def : definitions) {
            if (def.requiresApproval()) names.add(def.name());
        }
        return names;
    }


    public static ToolResponseMessage.ToolResponse executeSingleToolCall(
            org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService toolService,
            AssistantMessage.ToolCall call,
            Map<String, Object> stateData) {
        ToolCallback cb = toolService.agentFunction(call.name())
                .orElseThrow(() -> new IllegalStateException("Tool is not registered: " + call.name()));
        String result = cb.call(call.arguments(), new ToolContext(stateData));
        log.debug("[parallel_executor] Tool {} completed", call.name());
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(), result);
    }

    public static Map<String, Object> parseArguments(String arguments) {
        try {
            return MAPPER.readValue(arguments, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

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

    public static ToolResponseMessage buildRejectionMessage(AssistantMessage.ToolCall denied) {
        String responseData = "Execution of tool '%s' was rejected by the user.".formatted(denied.name());
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        denied.id(), denied.name(), responseData)))
                .build();
    }

    public static ToolResponseMessage buildToolResponseMessage(String toolCallId,
                                                                String toolName,
                                                                String responseData) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        toolCallId, toolName, responseData)))
                .build();
    }


    public static void logLlmRequest(String systemPrompt, List<Message> messages) {
        String systemPromptText = safeText(systemPrompt);
        String messageListText = formatMessages(messages);
        log.info("[model] Calling LLM, systemPromptChars={}, messageCount={}",
                systemPromptText.length(), messages.size());
        writePromptDebugFiles(systemPromptText, messageListText);
    }

    public static void logLlmResponse(AssistantMessage output, String finishReason) {
        log.info("[model] LLM response: hasToolCalls={}, toolCount={}, finishReason={}, text={}",
                output.hasToolCalls(),
                output.hasToolCalls() ? output.getToolCalls().size() : 0,
                finishReason,
                truncate(output.getText(), 100));
        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(tc ->
                    log.info("[model]   tool_call: name={}, args={}", tc.name(), truncate(tc.arguments(), 120)));
        }
    }


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
            log.warn("[model] Failed to write prompt debug file: {}", e.getMessage());
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
