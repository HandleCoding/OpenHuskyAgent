package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigChannelBindingResolver implements ChannelBindingResolver {
    private final ChannelBindingProperties properties;

    @Override
    public Optional<ChannelInstanceBinding> resolve(ChannelIdentity identity) {
        return resolveConfigured(identity).filter(ChannelInstanceBinding::enabled);
    }

    @Override
    public Optional<ChannelInstanceBinding> resolveConfigured(ChannelIdentity identity) {
        if (identity == null || identity.getChannelType() == null || isBlank(identity.getPlatformAccountId())) {
            return Optional.empty();
        }
        if (properties.getBindings() == null || properties.getBindings().isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, ChannelBindingProperties.BindingProperties> entry : properties.getBindings().entrySet()) {
            ChannelInstanceBinding binding = toBinding(entry.getKey(), entry.getValue()).orElse(null);
            if (binding == null) {
                continue;
            }
            if (binding.channelType() == identity.getChannelType()
                    && binding.platformAccountId().equals(identity.getPlatformAccountId())) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> defaultScene() {
        return isBlank(properties.getDefaultScene()) ? Optional.empty() : Optional.of(properties.getDefaultScene());
    }

    @Override
    public boolean allowsExplicitSceneOverride(ChannelIdentity identity) {
        if (identity == null || identity.getChannelType() == null) {
            return false;
        }
        if (properties.getAllowExplicitSceneOverrideFor() == null || properties.getAllowExplicitSceneOverrideFor().isEmpty()) {
            return false;
        }
        return properties.getAllowExplicitSceneOverrideFor().stream()
                .filter(value -> !isBlank(value))
                .anyMatch(value -> parseChannelType(value)
                        .map(type -> type == identity.getChannelType())
                        .orElse(false));
    }

    private Optional<ChannelInstanceBinding> toBinding(String bindingId, ChannelBindingProperties.BindingProperties props) {
        if (props == null) {
            return Optional.empty();
        }
        ChannelType channelType = parseChannelType(props.getChannelType()).orElse(null);
        if (channelType == null) {
            if (!isBlank(props.getChannelType())) {
                log.warn("Unknown channel binding channel-type: bindingId={}, channelType={}", bindingId, props.getChannelType());
            }
            return Optional.empty();
        }
        if (isBlank(props.getPlatformAccountId()) || isBlank(props.getSceneId())) {
            log.warn("Invalid channel binding: bindingId={}, channelType={}, platformAccountId={}, sceneId={}",
                    bindingId, props.getChannelType(), props.getPlatformAccountId(), props.getSceneId());
            return Optional.empty();
        }
        return Optional.of(new ChannelInstanceBinding(
                bindingId,
                channelType,
                props.getPlatformAccountId(),
                props.getSceneId(),
                props.isEnabled(),
                props.getDisplayName(),
                props.getMetadata() != null ? Map.copyOf(props.getMetadata()) : Map.of()
        ));
    }

    private Optional<ChannelType> parseChannelType(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (ChannelType type : ChannelType.values()) {
            if (type.name().equals(normalized) || type.getName().equalsIgnoreCase(value.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
