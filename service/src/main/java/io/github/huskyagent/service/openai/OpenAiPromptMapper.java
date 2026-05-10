package io.github.huskyagent.service.openai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class OpenAiPromptMapper {

    MappedPrompt map(OpenAiChatCompletionRequest request) {
        if (request == null) {
            throw new OpenAiProtocolException("Missing request body", "messages", "missing_request_body");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new OpenAiProtocolException("messages must not be empty", "messages", "missing_messages");
        }

        List<Message> messages = new ArrayList<>();
        String displayText = null;
        for (OpenAiChatCompletionRequest.Message message : request.messages()) {
            String role = normalizeRole(message.role());
            if (hasToolCalls(message.toolCalls())) {
                throw new OpenAiProtocolException("Assistant tool calls are not supported", "messages.tool_calls", "unsupported_tool_calls");
            }
            String content = textContent(message.content(), "messages.content");
            switch (role) {
                case "system", "developer" -> messages.add(new SystemMessage(content));
                case "user" -> {
                    messages.add(new UserMessage(content));
                    if (!content.isBlank()) {
                        displayText = content;
                    }
                }
                case "assistant" -> messages.add(new AssistantMessage(content));
                case "tool" -> throw new OpenAiProtocolException("Unsupported message role: tool", "messages", "unsupported_message_role");
                default -> throw new OpenAiProtocolException("Unsupported message role: " + role, "messages", "unsupported_message_role");
            }
        }
        if (messages.isEmpty()) {
            throw new OpenAiProtocolException("messages must contain text content", "messages", "empty_messages");
        }
        if (displayText == null) {
            displayText = messages.get(messages.size() - 1).getText();
        }
        if (displayText == null || displayText.isBlank()) {
            throw new OpenAiProtocolException("messages must contain text content", "messages", "empty_messages");
        }
        return new MappedPrompt(List.copyOf(messages), displayText);
    }

    String toPrompt(OpenAiChatCompletionRequest request) {
        return map(request).displayText();
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new OpenAiProtocolException("Message role is required", "messages.role", "missing_message_role");
        }
        return role.toLowerCase(Locale.ROOT);
    }

    private boolean hasToolCalls(Object toolCalls) {
        if (toolCalls == null) {
            return false;
        }
        if (toolCalls instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (toolCalls instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private String textContent(Object content, String param) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            return textContentParts(parts, param);
        }
        throw new OpenAiProtocolException("Only text message content is supported", param, "unsupported_content_type");
    }

    private String textContentParts(List<?> parts, String param) {
        List<String> texts = new ArrayList<>();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> map)) {
                throw new OpenAiProtocolException("Only text content parts are supported", param, "unsupported_content_type");
            }
            Object type = map.get("type");
            if (!(type instanceof String typeName) || !"text".equals(typeName)) {
                throw new OpenAiProtocolException("Only text content parts are supported", param, "unsupported_content_type");
            }
            Object text = map.get("text");
            if (text instanceof String value) {
                texts.add(value);
            }
        }
        return String.join("\n", texts);
    }

    record MappedPrompt(List<Message> messages, String displayText) {
    }
}
