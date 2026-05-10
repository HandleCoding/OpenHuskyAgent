package io.github.huskyagent.application;

import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentInputTest {

    @Test
    void structuredMessagesReportContentWithoutText() {
        List<Message> messages = List.of(new SystemMessage("Be concise"), new UserMessage("hello"));

        AgentInput input = AgentInput.structuredMessages(messages, null);

        assertTrue(input.hasContent());
        assertTrue(input.hasStructuredMessages());
        assertEquals(messages, input.structuredMessagesOrEmpty());
        assertTrue(input.getContentParts().isEmpty());
    }

    @Test
    void textOnlyKeepsExistingContentPartsBehavior() {
        AgentInput input = AgentInput.textOnly("hello");

        assertTrue(input.hasContent());
        assertFalse(input.hasStructuredMessages());
        assertEquals("hello", input.getText());
        assertEquals(1, input.getContentParts().size());
    }

    @Test
    void fromInboundKeepsExistingTextBehavior() {
        AgentInput input = AgentInput.fromInbound(InboundMessage.builder()
                .text("hello")
                .build());

        assertTrue(input.hasContent());
        assertFalse(input.hasStructuredMessages());
        assertEquals("hello", input.getText());
        assertEquals(1, input.getContentParts().size());
    }
}
