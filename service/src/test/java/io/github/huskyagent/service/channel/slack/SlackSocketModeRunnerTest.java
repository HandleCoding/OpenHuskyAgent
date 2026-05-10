package io.github.huskyagent.service.channel.slack;

import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SlackSocketModeRunnerTest {

    @Test
    void startsOnlyEnabledInstancesAndAllowsDisabledMissingTokens() {
        SlackProperties properties = new SlackProperties();
        SlackProperties.InstanceProperties disabled = instance("U1", "", "", false);
        properties.setInstances(Map.of("disabled", disabled));
        SlackSocketModeRunner runner = new SlackSocketModeRunner(registry(properties));

        assertDoesNotThrow(runner::start);
    }

    @Test
    void rejectsMissingEnabledBotToken() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of("assistant", instance("U1", "", "xapp-1", true)));
        SlackSocketModeRunner runner = new SlackSocketModeRunner(registry(properties));

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::start);
        assertTrue(error.getMessage().contains("bot token"));
    }

    @Test
    void rejectsMissingEnabledAppToken() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of("assistant", instance("U1", "xoxb-1", "", true)));
        SlackSocketModeRunner runner = new SlackSocketModeRunner(registry(properties));

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::start);
        assertTrue(error.getMessage().contains("app token"));
    }

    @Test
    void rejectsMissingEnabledBotUserId() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of("assistant", instance("", "xoxb-1", "xapp-1", true)));
        SlackSocketModeRunner runner = new SlackSocketModeRunner(registry(properties));

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::start);
        assertTrue(error.getMessage().contains("bot user id"));
    }

    @Test
    void rejectsDuplicateEnabledAppTokens() {
        SlackProperties properties = new SlackProperties();
        properties.setInstances(Map.of(
                "a", instance("U1", "xoxb-1", "xapp-1", true),
                "b", instance("U2", "xoxb-2", "xapp-1", true)
        ));
        SlackSocketModeRunner runner = new SlackSocketModeRunner(registry(properties));

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::start);
        assertTrue(error.getMessage().contains("Duplicate Slack app token"));
    }

    private SlackProperties.InstanceProperties instance(String botUserId, String botToken, String appToken, boolean enabled) {
        SlackProperties.InstanceProperties properties = new SlackProperties.InstanceProperties();
        properties.setBotUserId(botUserId);
        properties.setBotToken(botToken);
        properties.setAppToken(appToken);
        properties.setEnabled(enabled);
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
