package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.infra.channel.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeishuInstanceRegistryTest {

    @Test
    void createsInstanceForEachConfiguredInstanceAndFindsByInstanceId() {
        FeishuProperties properties = new FeishuProperties();
        properties.setInstances(Map.of(
                "assistant-bot", instance("cli_assistant", "ou_assistant"),
                "qa-bot", instance("cli_qa", "ou_qa")
        ));
        FeishuInstanceRegistry registry = registry(properties);

        assertEquals(2, registry.all().size());
        assertTrue(registry.find("assistant-bot").isPresent());
        assertTrue(registry.find("qa-bot").isPresent());
        assertEquals(ChannelType.FEISHU, registry.channelType());
    }

    @Test
    void fallsBackToBotOpenIdWhenAppIdIsBlank() {
        FeishuProperties properties = new FeishuProperties();
        properties.setInstances(Map.of("bot-open-id-only", instance("", "ou_bot")));
        FeishuInstanceRegistry registry = registry(properties);

        FeishuInstance instance = registry.find("bot-open-id-only").orElseThrow();
        assertEquals("ou_bot", instance.properties().getBotOpenId());
    }

    private FeishuProperties.InstanceProperties instance(String appId, String botOpenId) {
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setAppId(appId);
        properties.setBotOpenId(botOpenId);
        properties.setVerificationToken("token");
        return properties;
    }

    private FeishuInstanceRegistry registry(FeishuProperties properties) {
        return new FeishuInstanceRegistry(
                properties,
                new ObjectMapper(),
                new ToolDisplayMessageRenderer(),
                mock(ChannelRuntimeService.class),
                new FeishuInboundDeduplicator(),
                Runnable::run
        );
    }
}