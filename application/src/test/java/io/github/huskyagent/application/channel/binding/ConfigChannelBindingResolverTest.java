package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigChannelBindingResolverTest {

    @Test
    void resolvesMatchingBindingByChannelTypeAndPlatformAccountId() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of(
                "feishu-assistant", binding("feishu", "cli_assistant", "assistant", true),
                "feishu-qa", binding("feishu", "cli_qa", "feishu-qa", true)
        ));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        ChannelInstanceBinding resolved = resolver.resolve(identity(ChannelType.FEISHU, "cli_qa")).orElseThrow();

        assertEquals("feishu-qa", resolved.bindingId());
        assertEquals("feishu-qa", resolved.sceneId());
    }

    @Test
    void resolvesTelegramBindingWithLeadingAtUsername() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of("telegram-assistant", binding("telegram", "@assistant_bot", "assistant", true)));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        ChannelInstanceBinding resolved = resolver.resolve(identity(ChannelType.TELEGRAM, "assistant_bot")).orElseThrow();

        assertEquals("telegram-assistant", resolved.bindingId());
        assertEquals("assistant", resolved.sceneId());
    }

    @Test
    void resolvesSlackBindingByBotUserId() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of("slack-assistant", binding("slack", "U123BOT", "assistant", true)));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        ChannelInstanceBinding resolved = resolver.resolve(identity(ChannelType.SLACK, "U123BOT")).orElseThrow();

        assertEquals("slack-assistant", resolved.bindingId());
        assertEquals("assistant", resolved.sceneId());
    }
    @Test
    void ignoresDisabledBinding() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of("feishu-qa", binding("feishu", "cli_qa", "feishu-qa", false)));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        assertTrue(resolver.resolve(identity(ChannelType.FEISHU, "cli_qa")).isEmpty());
    }

    @Test
    void exposesConfiguredDisabledBindingSeparatelyFromActiveResolution() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of("http-chatbot", binding("http", "chatbot", "chatbot", false)));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        assertTrue(resolver.resolve(identity(ChannelType.HTTP, "chatbot")).isEmpty());
        ChannelInstanceBinding configured = resolver.resolveConfigured(identity(ChannelType.HTTP, "chatbot")).orElseThrow();
        assertFalse(configured.enabled());
        assertEquals("chatbot", configured.sceneId());
    }

    @Test
    void returnsEmptyWhenPlatformAccountIdMissing() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setBindings(Map.of("feishu-qa", binding("feishu", "cli_qa", "feishu-qa", true)));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        assertTrue(resolver.resolve(identity(ChannelType.FEISHU, null)).isEmpty());
    }

    @Test
    void exposesGlobalDefaultScene() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setDefaultScene("assistant");
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        assertEquals("assistant", resolver.defaultScene().orElseThrow());
    }

    @Test
    void explicitSceneOverrideIsControlledByConfiguredChannelTypes() {
        ChannelBindingProperties properties = new ChannelBindingProperties();
        properties.setAllowExplicitSceneOverrideFor(Set.of("http"));
        ConfigChannelBindingResolver resolver = new ConfigChannelBindingResolver(properties);

        assertTrue(resolver.allowsExplicitSceneOverride(identity(ChannelType.HTTP, "chatbot")));
        assertFalse(resolver.allowsExplicitSceneOverride(identity(ChannelType.FEISHU, "cli_qa")));
    }

    private ChannelIdentity identity(ChannelType channelType, String platformAccountId) {
        return ChannelIdentity.builder()
                .channelType(channelType)
                .platformAccountId(platformAccountId)
                .build();
    }

    private ChannelBindingProperties.BindingProperties binding(String channelType, String accountId,
                                                               String sceneId, boolean enabled) {
        ChannelBindingProperties.BindingProperties binding = new ChannelBindingProperties.BindingProperties();
        binding.setChannelType(channelType);
        binding.setPlatformAccountId(accountId);
        binding.setSceneId(sceneId);
        binding.setEnabled(enabled);
        return binding;
    }
}
