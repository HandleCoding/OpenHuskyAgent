package io.github.huskyagent.service.channel;

import io.github.huskyagent.application.channel.binding.ChannelInstanceReference;
import io.github.huskyagent.application.channel.binding.ChannelInstanceReferenceResolver;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.service.channel.feishu.FeishuProperties;
import io.github.huskyagent.service.channel.slack.SlackProperties;
import io.github.huskyagent.service.channel.telegram.TelegramProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ChannelInstanceReferenceConfiguration implements ChannelInstanceReferenceResolver {
    private final FeishuProperties feishuProperties;
    private final SlackProperties slackProperties;
    private final TelegramProperties telegramProperties;

    public ChannelInstanceReferenceConfiguration(FeishuProperties feishuProperties,
                                                 SlackProperties slackProperties,
                                                 TelegramProperties telegramProperties) {
        this.feishuProperties = feishuProperties;
        this.slackProperties = slackProperties;
        this.telegramProperties = telegramProperties;
    }

    @Override
    public Optional<ChannelInstanceReference> resolve(ChannelType channelType, String instanceId) {
        if (channelType == null || isBlank(instanceId)) {
            return Optional.empty();
        }
        return switch (channelType) {
            case FEISHU -> feishu(instanceId);
            case SLACK -> slack(instanceId);
            case TELEGRAM -> telegram(instanceId);
            case HTTP, TUI -> direct(channelType, instanceId);
        };
    }

    private Optional<ChannelInstanceReference> feishu(String instanceId) {
        FeishuProperties.InstanceProperties props = feishuProperties.getInstances().get(instanceId);
        if (props == null) {
            return Optional.empty();
        }
        String platformAccountId = firstNonBlank(props.getAppId(), props.getBotOpenId());
        return Optional.of(new ChannelInstanceReference(ChannelType.FEISHU, instanceId, props.isEnabled(), platformAccountId));
    }

    private Optional<ChannelInstanceReference> slack(String instanceId) {
        SlackProperties.InstanceProperties props = slackProperties.getInstances().get(instanceId);
        if (props == null) {
            return Optional.empty();
        }
        return Optional.of(new ChannelInstanceReference(ChannelType.SLACK, instanceId, props.isEnabled(), props.getBotUserId()));
    }

    private Optional<ChannelInstanceReference> telegram(String instanceId) {
        TelegramProperties.InstanceProperties props = telegramProperties.getInstances().get(instanceId);
        if (props == null) {
            return Optional.empty();
        }
        return Optional.of(new ChannelInstanceReference(
                ChannelType.TELEGRAM,
                instanceId,
                props.isEnabled(),
                normalizeTelegramUsername(props.getBotUsername())));
    }

    private Optional<ChannelInstanceReference> direct(ChannelType channelType, String instanceId) {
        return Optional.of(new ChannelInstanceReference(channelType, instanceId, true, instanceId));
    }

    private String firstNonBlank(String primary, String fallback) {
        return !isBlank(primary) ? primary : fallback;
    }

    private String normalizeTelegramUsername(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
