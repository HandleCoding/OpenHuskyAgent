package io.github.huskyagent.application;

import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.MessageAttachment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MultimodalMessageBuilder {

    private static final String DEFAULT_IMAGE_PROMPT = "Please analyze the attached image.";

    public UserMessage buildUserMessage(AgentInput input) {
        AgentInput safeInput = input != null ? input : AgentInput.textOnly("");
        List<Media> media = imageMedia(safeInput.getContentParts());
        String text = normalizedText(safeInput, media);
        if (media.isEmpty()) {
            return new UserMessage(text);
        }
        log.info("Building multimodal UserMessage: imageCount={}, mimeTypes={}, textLength={}",
                media.size(),
                media.stream().map(m -> m.getMimeType().toString()).toList(),
                text.length());
        return UserMessage.builder()
                .text(text)
                .media(media)
                .build();
    }

    public String persistenceText(AgentInput input) {
        AgentInput safeInput = input != null ? input : AgentInput.textOnly("");
        if (safeInput.hasStructuredMessages()) {
            return structuredPersistenceText(safeInput.structuredMessagesOrEmpty());
        }
        StringBuilder text = new StringBuilder(safeInput.getText() != null ? safeInput.getText().trim() : "");
        List<MessageAttachment> attachments = attachments(safeInput.getContentParts());
        for (MessageAttachment attachment : attachments) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append('[')
                    .append(attachmentLabel(attachment))
                    .append(" attached");
            if (attachment.getMimeType() != null && !attachment.getMimeType().isBlank()) {
                text.append(": ").append(attachment.getMimeType());
            }
            if (attachment.getSizeBytes() != null) {
                text.append(", ").append(attachment.getSizeBytes()).append(" bytes");
            }
            text.append(']');
        }
        return text.toString();
    }

    private String structuredPersistenceText(List<Message> messages) {
        return messages.stream()
                .filter(message -> message != null && message.getText() != null && !message.getText().isBlank())
                .map(message -> roleLabel(message) + ": " + message.getText().trim())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private String roleLabel(Message message) {
        if (message instanceof UserMessage) {
            return "User";
        }
        if (message instanceof AssistantMessage) {
            return "Assistant";
        }
        if (message instanceof SystemMessage) {
            return "System";
        }
        return message.getMessageType().getValue();
    }

    private List<Media> imageMedia(List<InboundContentPart> parts) {
        List<Media> media = new ArrayList<>();
        for (MessageAttachment attachment : attachments(parts)) {
            if (attachment.getKind() != MessageAttachment.Kind.IMAGE) {
                continue;
            }
            MimeType mimeType = attachment.getMimeType() != null && !attachment.getMimeType().isBlank()
                    ? MimeTypeUtils.parseMimeType(attachment.getMimeType())
                    : MimeTypeUtils.IMAGE_JPEG;
            if (attachment.getData() != null && attachment.getData().length > 0) {
                media.add(new Media(mimeType, new ByteArrayResource(attachment.getData())));
            } else if (attachment.getUri() != null) {
                media.add(Media.builder().mimeType(mimeType).data(attachment.getUri()).build());
            }
        }
        return List.copyOf(media);
    }

    private String normalizedText(AgentInput input, List<Media> media) {
        String text = input.getText() != null ? input.getText().trim() : "";
        if (!text.isBlank()) {
            return text;
        }
        String joinedTextParts = textParts(input.getContentParts());
        if (!joinedTextParts.isBlank()) {
            return joinedTextParts;
        }
        return media.isEmpty() ? "" : DEFAULT_IMAGE_PROMPT;
    }

    private String textParts(List<InboundContentPart> parts) {
        if (parts == null) {
            return "";
        }
        List<String> values = parts.stream()
                .filter(part -> part != null && part.getKind() == InboundContentPart.Kind.TEXT)
                .map(InboundContentPart::getText)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .toList();
        return String.join("\n", values);
    }

    private List<MessageAttachment> attachments(List<InboundContentPart> parts) {
        if (parts == null) {
            return List.of();
        }
        return parts.stream()
                .filter(part -> part != null && part.getAttachment() != null)
                .map(InboundContentPart::getAttachment)
                .toList();
    }

    private String attachmentLabel(MessageAttachment attachment) {
        if (attachment.getKind() == null) {
            return "file";
        }
        return attachment.getKind().name().toLowerCase();
    }
}
