package io.github.huskyagent.application;

import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class AgentInput {
    String text;
    List<InboundContentPart> contentParts;

    public static AgentInput textOnly(String text) {
        return AgentInput.builder()
                .text(text)
                .contentParts(text != null && !text.isBlank() ? List.of(InboundContentPart.text(text)) : List.of())
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
                .build();
    }

    public boolean hasContent() {
        if (text != null && !text.isBlank()) {
            return true;
        }
        return contentParts != null && contentParts.stream().anyMatch(part -> part != null
                && (part.getAttachment() != null || (part.getText() != null && !part.getText().isBlank())));
    }
}
