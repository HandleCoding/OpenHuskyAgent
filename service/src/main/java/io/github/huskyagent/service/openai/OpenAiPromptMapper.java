package io.github.huskyagent.service.openai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class OpenAiPromptMapper {

    String toPrompt(OpenAiChatCompletionRequest request) {
        if (request == null) {
            throw new OpenAiProtocolException("Missing request body", "messages", "missing_request_body");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new OpenAiProtocolException("messages must not be empty", "messages", "missing_messages");
        }

        List<String> lines = new ArrayList<>();
        for (OpenAiChatCompletionRequest.Message message : request.messages()) {
            String role = normalizeRole(message.role());
            String content = textContent(message.content(), "messages.content");
            switch (role) {
                case "system" -> lines.add("System: " + content);
                case "user" -> lines.add("User: " + content);
                case "assistant" -> lines.add("Assistant: " + content);
                case "tool" -> lines.add("Tool: " + content);
                case "developer" -> throw new OpenAiProtocolException("Unsupported message role: developer", "messages", "unsupported_message_role");
                default -> throw new OpenAiProtocolException("Unsupported message role: " + role, "messages", "unsupported_message_role");
            }
        }
        String prompt = String.join("\n\n", lines).trim();
        if (prompt.isBlank()) {
            throw new OpenAiProtocolException("messages must contain text content", "messages", "empty_messages");
        }
        return prompt;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new OpenAiProtocolException("Message role is required", "messages.role", "missing_message_role");
        }
        return role.toLowerCase(Locale.ROOT);
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
}
