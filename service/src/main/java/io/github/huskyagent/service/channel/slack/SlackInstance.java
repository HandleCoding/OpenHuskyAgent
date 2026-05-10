package io.github.huskyagent.service.channel.slack;

import com.slack.api.bolt.App;

public record SlackInstance(String instanceId,
                            SlackProperties.InstanceProperties properties,
                            SlackApiClient apiClient,
                            SlackInstanceAdapter adapter,
                            SlackInstanceEventHandler eventHandler,
                            App app) {
}
