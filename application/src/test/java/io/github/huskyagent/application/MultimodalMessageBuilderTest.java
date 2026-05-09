package io.github.huskyagent.application;

import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.MessageAttachment;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultimodalMessageBuilderTest {

    private final MultimodalMessageBuilder builder = new MultimodalMessageBuilder();

    @Test
    void buildsTextOnlyUserMessage() {
        UserMessage message = builder.buildUserMessage(AgentInput.textOnly("hello"));

        assertEquals("hello", message.getText());
        assertTrue(message.getMedia().isEmpty());
    }

    @Test
    void buildsTextAndImageUserMessage() {
        byte[] image = new byte[]{1, 2, 3};
        AgentInput input = AgentInput.builder()
                .text("what is this")
                .contentParts(List.of(InboundContentPart.attachment(MessageAttachment.builder()
                        .kind(MessageAttachment.Kind.IMAGE)
                        .mimeType("image/png")
                        .data(image)
                        .build())))
                .build();

        UserMessage message = builder.buildUserMessage(input);

        assertEquals("what is this", message.getText());
        assertEquals(1, message.getMedia().size());
        assertEquals("image/png", message.getMedia().get(0).getMimeType().toString());
        assertArrayEquals(image, message.getMedia().get(0).getDataAsByteArray());
    }

    @Test
    void buildsImageOnlyUserMessageWithDefaultPrompt() {
        AgentInput input = AgentInput.builder()
                .contentParts(List.of(InboundContentPart.attachment(MessageAttachment.builder()
                        .kind(MessageAttachment.Kind.IMAGE)
                        .mimeType("image/jpeg")
                        .data(new byte[]{9})
                        .build())))
                .build();

        UserMessage message = builder.buildUserMessage(input);

        assertEquals("Please analyze the attached image.", message.getText());
        assertEquals(1, message.getMedia().size());
    }

    @Test
    void summarizesAttachmentsForPersistence() {
        AgentInput input = AgentInput.builder()
                .text("caption")
                .contentParts(List.of(InboundContentPart.attachment(MessageAttachment.builder()
                        .kind(MessageAttachment.Kind.IMAGE)
                        .mimeType("image/webp")
                        .sizeBytes(12L)
                        .build())))
                .build();

        assertEquals("caption\n[image attached: image/webp, 12 bytes]", builder.persistenceText(input));
    }
}
