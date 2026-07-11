package io.github.huskyagent.infra.llm;

import io.github.huskyagent.infra.llm.api.LlmMessage;
import io.github.huskyagent.infra.llm.api.LlmResult;
import io.github.huskyagent.infra.llm.api.LlmToolCall;
import io.github.huskyagent.infra.llm.api.LlmToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps between Spring AI conversation messages (graph/checkpoint state) and neutral LLM transport types.
 */
public final class SpringAiLlmMessageMapper {

    private SpringAiLlmMessageMapper() {
    }

    public static List<LlmMessage> toLlmMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<LlmMessage> result = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage sm) {
                result.add(LlmMessage.system(text(sm)));
            } else if (message instanceof UserMessage um) {
                result.add(LlmMessage.user(text(um)));
            } else if (message instanceof AssistantMessage am) {
                result.add(LlmMessage.assistant(text(am), toLlmToolCalls(am)));
            } else if (message instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                    result.add(LlmMessage.tool(response.id(), response.responseData()));
                }
            } else if (message != null) {
                // fallback: treat as user text
                result.add(LlmMessage.user(text(message)));
            }
        }
        return List.copyOf(result);
    }

    public static List<LlmToolDefinition> toLlmTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<LlmToolDefinition> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            result.add(new LlmToolDefinition(tool.name(), tool.description(), tool.parametersSchema()));
        }
        return List.copyOf(result);
    }

    public static AssistantMessage toAssistantMessage(LlmResult result) {
        String text = result.text() != null ? result.text() : "";
        String reasoning = result.reasoning();
        List<AssistantMessage.ToolCall> toolCalls = toSpringToolCalls(result.toolCalls());
        AssistantMessage.Builder builder = AssistantMessage.builder().content(text).toolCalls(toolCalls);
        if (reasoning != null && !reasoning.isBlank()) {
            builder.properties(Map.of("reasoningContent", reasoning));
        }
        return builder.build();
    }

    private static List<LlmToolCall> toLlmToolCalls(AssistantMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return List.of();
        }
        List<LlmToolCall> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : message.getToolCalls()) {
            calls.add(new LlmToolCall(tc.id(), tc.name(), tc.arguments() != null ? tc.arguments() : "{}"));
        }
        return List.copyOf(calls);
    }

    private static List<AssistantMessage.ToolCall> toSpringToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (LlmToolCall call : toolCalls) {
            result.add(new AssistantMessage.ToolCall(
                    call.id() != null ? call.id() : "",
                    "function",
                    call.name() != null ? call.name() : "",
                    call.argumentsJson() != null ? call.argumentsJson() : "{}"));
        }
        return List.copyOf(result);
    }

    private static String text(Message message) {
        if (message == null) {
            return "";
        }
        String t = message.getText();
        return t != null ? t : "";
    }
}
