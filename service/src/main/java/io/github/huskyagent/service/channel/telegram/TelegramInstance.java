package io.github.huskyagent.service.channel.telegram;

public record TelegramInstance(String instanceId,
                               TelegramProperties.InstanceProperties properties,
                               TelegramApiClient apiClient,
                               TelegramInstanceAdapter adapter,
                               TelegramInstanceEventHandler eventHandler) {
}
