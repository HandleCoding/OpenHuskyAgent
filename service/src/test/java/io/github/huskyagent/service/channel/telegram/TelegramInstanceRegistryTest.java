package io.github.huskyagent.service.channel.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class TelegramInstanceRegistryTest {

    @Test
    void createsInstanceForEachConfiguredInstanceAndFindsByInstanceId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setInstances(Map.of(
                "assistant-bot", instance("assistant_bot"),
                "qa-bot", instance("qa_bot")
        ));
        TelegramInstanceRegistry registry = registry(properties);

        assertEquals(2, registry.all().size());
        assertTrue(registry.find("assistant-bot").isPresent());
        assertTrue(registry.find("qa-bot").isPresent());
        assertEquals(ChannelType.TELEGRAM, registry.channelType());
        assertEquals("assistant_bot", registry.find("assistant-bot").orElseThrow().adapter().platformAccountId());
    }

    @Test
    void routesOutboundByBotUsernamePlatformAccountId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setInstances(Map.of("assistant-bot", instance("assistant_bot")));
        TelegramInstanceRegistry registry = registry(properties);
        OutboundMessage message = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TELEGRAM)
                        .platformAccountId("assistant_bot")
                        .chatId("1001")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("1001").messageId("11").build())
                .text("hello")
                .build();

        assertDoesNotThrow(() -> registry.send(message));
    }

    @Test
    void routesOutboundByNormalizedBotUsernamePlatformAccountId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setInstances(Map.of("assistant-bot", instance("@assistant_bot")));
        TelegramInstanceRegistry registry = registry(properties);
        OutboundMessage message = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TELEGRAM)
                        .platformAccountId("assistant_bot")
                        .chatId("1001")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("1001").messageId("11").build())
                .text("hello")
                .build();

        assertDoesNotThrow(() -> registry.send(message));
    }
    @Test
    void missingPlatformAccountIdThrowsUsefulError() {
        TelegramProperties properties = new TelegramProperties();
        properties.setInstances(Map.of("assistant-bot", instance("assistant_bot")));
        TelegramInstanceRegistry registry = registry(properties);
        OutboundMessage message = OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .channelIdentity(ChannelIdentity.builder().channelType(ChannelType.TELEGRAM).build())
                .replyTarget(ReplyTarget.builder().chatId("1001").messageId("11").build())
                .text("hello")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry.send(message));
        assertTrue(error.getMessage().contains("blank platformAccountId"));
    }

    private TelegramProperties.InstanceProperties instance(String botUsername) {
        TelegramProperties.InstanceProperties properties = new TelegramProperties.InstanceProperties();
        properties.setBotUsername(botUsername);
        properties.setToken("");
        return properties;
    }

    private TelegramInstanceRegistry registry(TelegramProperties properties) {
        return new TelegramInstanceRegistry(
                properties,
                new ObjectMapper(),
                new ToolDisplayMessageRenderer(),
                mock(ChannelRuntimeService.class),
                new TelegramInboundDeduplicator(),
                Runnable::run
        );
    }
}
