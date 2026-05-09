package io.github.huskyagent.service.channel.feishu;

public record FeishuInstance(
        String instanceId,
        FeishuProperties.InstanceProperties properties,
        FeishuApiClient apiClient,
        FeishuInstanceAdapter adapter,
        FeishuInstanceEventHandler eventHandler
) {
}