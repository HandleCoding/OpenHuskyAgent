package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigChannelBindingResolverTest {

    @Test
    void resolvesAgentBindingByChannelInstancePlatformAccountId() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu:assistant-bot")),
                Map.of("feishu:assistant-bot", ref(ChannelType.FEISHU, "assistant-bot", true, "cli_assistant"))
        );

        ChannelInstanceBinding resolved = resolver.resolve(identity(ChannelType.FEISHU, "cli_assistant")).orElseThrow();

        assertEquals("assistant", resolved.sceneId());
        assertEquals("assistant@feishu:assistant-bot", resolved.bindingId());
    }

    @Test
    void oneAgentCanBindMultipleChannelInstances() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu:assistant-bot", "slack:assistant-bot")),
                Map.of(
                        "feishu:assistant-bot", ref(ChannelType.FEISHU, "assistant-bot", true, "cli_assistant"),
                        "slack:assistant-bot", ref(ChannelType.SLACK, "assistant-bot", true, "U123BOT")
                )
        );

        assertEquals("assistant", resolver.resolve(identity(ChannelType.FEISHU, "cli_assistant")).orElseThrow().sceneId());
        assertEquals("assistant", resolver.resolve(identity(ChannelType.SLACK, "U123BOT")).orElseThrow().sceneId());
    }

    @Test
    void oneAgentCanBindMultipleInstancesOfSameChannelType() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("support", List.of("feishu:support-cn", "feishu:support-global")),
                Map.of(
                        "feishu:support-cn", ref(ChannelType.FEISHU, "support-cn", true, "cli_cn"),
                        "feishu:support-global", ref(ChannelType.FEISHU, "support-global", true, "cli_global")
                )
        );

        assertEquals("support", resolver.resolve(identity(ChannelType.FEISHU, "cli_cn")).orElseThrow().sceneId());
        assertEquals("support", resolver.resolve(identity(ChannelType.FEISHU, "cli_global")).orElseThrow().sceneId());
    }

    @Test
    void ignoresDisabledReferencedInstance() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu:assistant-bot")),
                Map.of("feishu:assistant-bot", ref(ChannelType.FEISHU, "assistant-bot", false, ""))
        );

        assertTrue(resolver.resolve(identity(ChannelType.FEISHU, "cli_assistant")).isEmpty());
    }

    @Test
    void resolvesTelegramBindingWithLeadingAtUsername() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("telegram:assistant-bot")),
                Map.of("telegram:assistant-bot", ref(ChannelType.TELEGRAM, "assistant-bot", true, "@assistant_bot"))
        );

        ChannelInstanceBinding resolved = resolver.resolve(identity(ChannelType.TELEGRAM, "assistant_bot")).orElseThrow();

        assertEquals("assistant", resolved.sceneId());
    }

    @Test
    void duplicateChannelRefAcrossAgentsFails() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of(
                        "assistant", List.of("feishu:assistant-bot"),
                        "support", List.of("feishu:assistant-bot")
                ),
                Map.of("feishu:assistant-bot", ref(ChannelType.FEISHU, "assistant-bot", true, "cli_assistant"))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, resolver::validate);

        assertTrue(error.getMessage().contains("multiple agents"));
    }

    @Test
    void invalidChannelRefFails() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu")),
                Map.of()
        );

        assertThrows(IllegalArgumentException.class, resolver::validate);
    }

    @Test
    void unknownChannelTypeFails() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("discord:bot")),
                Map.of()
        );

        assertThrows(IllegalArgumentException.class, resolver::validate);
    }

    @Test
    void unknownInstanceFails() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu:missing")),
                Map.of()
        );

        assertThrows(IllegalArgumentException.class, resolver::validate);
    }

    @Test
    void blankPlatformAccountForEnabledInstanceFails() {
        ConfigChannelBindingResolver resolver = resolver(
                Map.of("assistant", List.of("feishu:assistant-bot")),
                Map.of("feishu:assistant-bot", ref(ChannelType.FEISHU, "assistant-bot", true, ""))
        );

        assertThrows(IllegalArgumentException.class, resolver::validate);
    }

    private ConfigChannelBindingResolver resolver(Map<String, List<String>> bindings,
                                                  Map<String, ChannelInstanceReference> references) {
        AgentChannelBindingProperties properties = new AgentChannelBindingProperties();
        properties.setBindings(bindings);
        return new ConfigChannelBindingResolver(properties, (channelType, instanceId) ->
                Optional.ofNullable(references.get(channelType.getName() + ":" + instanceId)));
    }

    private ChannelInstanceReference ref(ChannelType channelType, String instanceId,
                                         boolean enabled, String platformAccountId) {
        return new ChannelInstanceReference(channelType, instanceId, enabled, platformAccountId);
    }

    private ChannelIdentity identity(ChannelType channelType, String platformAccountId) {
        return ChannelIdentity.builder()
                .channelType(channelType)
                .platformAccountId(platformAccountId)
                .build();
    }
}
