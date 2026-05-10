package io.github.huskyagent.service.channel.slack;

import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.ReplyTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SlackInstanceRegistryTest {

    @Test
    void createsInstanceForEachConfiguredInstanceAndFindsByInstanceId() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of(
                "assistant-bot", instance("U111"),
                "qa-bot", instance("U222")
        ));
        SlackInstanceRegistry registry = registry(properties);

        assertEquals(2, registry.all().size());
        assertTrue(registry.find("assistant-bot").isPresent());
        assertTrue(registry.find("qa-bot").isPresent());
        assertEquals(ChannelType.SLACK, registry.channelType());
        assertEquals("U111", registry.find("assistant-bot").orElseThrow().adapter().platformAccountId());
    }

    @Test
    void routesOutboundByBotUserIdPlatformAccountId() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of("assistant-bot", instance("U111")));
        SlackInstanceRegistry registry = registry(properties);
        OutboundMessage message = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.SLACK)
                        .platformAccountId("U111")
                        .chatId("C1")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("C1").messageId("1710000000.000100").build())
                .text("hello")
                .build();

        assertDoesNotThrow(() -> registry.send(message));
    }

    @Test
    void missingPlatformAccountIdThrowsUsefulError() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of("assistant-bot", instance("U111")));
        SlackInstanceRegistry registry = registry(properties);
        OutboundMessage message = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .channelIdentity(ChannelIdentity.builder().channelType(ChannelType.SLACK).build())
                .replyTarget(ReplyTarget.builder().chatId("C1").messageId("1710000000.000100").build())
                .text("hello")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry.send(message));
        assertTrue(error.getMessage().contains("blank platformAccountId"));
    }

    private SlackProperties.InstanceProperties instance(String botUserId) {
        SlackProperties.InstanceProperties properties = new SlackProperties.InstanceProperties();
        properties.setBotUserId(botUserId);
        properties.setBotToken("");
        return properties;
    }

    private SlackInstanceRegistry registry(SlackProperties properties) {
        return new SlackInstanceRegistry(
                properties,
                new ToolDisplayMessageRenderer(),
                mock(ChannelRuntimeService.class),
                new SlackInboundDeduplicator(),
                Runnable::run
        );
    }
}
