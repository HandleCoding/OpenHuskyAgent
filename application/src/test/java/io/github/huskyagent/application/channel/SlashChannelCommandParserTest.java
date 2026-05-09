package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashChannelCommandParserTest {

    private final SlashChannelCommandParser parser = new SlashChannelCommandParser();

    @Test
    void parsesSlashCommandWithArgs() {
        var command = parser.parse(InboundMessage.builder()
                .text("/scene feishu-qa")
                .build());

        assertTrue(command.isPresent());
        assertEquals("scene", command.get().name());
        assertEquals("feishu-qa", command.get().args());
    }

    @Test
    void ignoresNormalText() {
        assertTrue(parser.parse(InboundMessage.builder()
                .text("hello /new")
                .build()).isEmpty());
    }
}
