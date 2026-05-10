package io.github.huskyagent.application;

import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.Builder;
import lombok.Value;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class AgentInput {
    String text;
    List<InboundContentPart> contentParts;
    List<Message> messages;

    public static AgentInput textOnly(String text) {
        return AgentInput.builder()
                .text(text)
                .contentParts(text != null && !text.isBlank() ? List.of(InboundContentPart.text(text)) : List.of())
                .messages(List.of())
                .build();
    }

    public static AgentInput structuredMessages(List<Message> messages, String displayText) {
        List<Message> safeMessages = messages != null ? List.copyOf(messages) : List.of();
        return AgentInput.builder()
                .text(displayText)
                .contentParts(displayText != null && !displayText.isBlank() ? List.of(InboundContentPart.text(displayText)) : List.of())
                .messages(safeMessages)
                .build();
    }

    public static AgentInput fromInbound(InboundMessage inbound) {
        if (inbound == null) {
            return textOnly("");
        }
        List<InboundContentPart> parts = inbound.getContentParts() != null
                ? new ArrayList<>(inbound.getContentParts())
                : new ArrayList<>();
        if (parts.isEmpty() && inbound.getText() != null && !inbound.getText().isBlank()) {
            parts.add(InboundContentPart.text(inbound.getText()));
        }
        return AgentInput.builder()
                .text(inbound.getText())
                .contentParts(List.copyOf(parts))
                .messages(List.of())
                .build();
    }

    public boolean hasStructuredMessages() {
        return messages != null && !messages.isEmpty();
    }

    public List<Message> structuredMessagesOrEmpty() {
        return messages != null ? List.copyOf(messages) : List.of();
    }

    public boolean hasContent() {
        if (hasStructuredMessages()) {
            return true;
        }
        if (text != null && !text.isBlank()) {
            return true;
        }
        return contentParts != null && contentParts.stream().anyMatch(part -> part != null
                && (part.getAttachment() != null || (part.getText() != null && !part.getText().isBlank())));
    }
}
