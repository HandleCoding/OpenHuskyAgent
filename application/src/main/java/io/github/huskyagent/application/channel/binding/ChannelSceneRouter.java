package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelSceneRouter {
    private final ChannelBindingResolver bindingResolver;
    private final SceneResolver sceneResolver;

    public EffectiveChannelRoute resolve(InboundMessage inbound) {
        if (inbound != null && !isBlank(inbound.getSceneId()) && allowsExplicitAgent(inbound.getChannelIdentity())) {
            requireScene(inbound.getSceneId(), "explicit scene override");
            return new EffectiveChannelRoute(inbound.getSceneId(), null, EffectiveChannelRoute.Source.EXPLICIT);
        }

        Optional<ChannelInstanceBinding> configuredBinding = inbound != null
                ? bindingResolver.resolveConfigured(inbound.getChannelIdentity())
                : Optional.empty();
        Optional<ChannelInstanceBinding> binding = configuredBinding.filter(ChannelInstanceBinding::enabled);
        if (binding.isPresent()) {
            ChannelInstanceBinding value = binding.get();
            requireScene(value.sceneId(), "channel binding " + value.bindingId());
            return new EffectiveChannelRoute(value.sceneId(), value.bindingId(), EffectiveChannelRoute.Source.BINDING);
        }

        throw new IllegalArgumentException("No agent binding for channel identity: " + describe(inbound));
    }

    private void requireScene(String sceneId, String source) {
        if (isBlank(sceneId)) {
            throw new IllegalArgumentException("Missing scene for " + source);
        }
        try {
            SceneConfig resolved = sceneResolver.resolve(sceneId);
            if (resolved == null || !sceneId.equals(resolved.getSceneId())) {
                throw new IllegalArgumentException("Unknown scene for " + source + ": " + sceneId);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown scene for " + source + ": " + sceneId, e);
        }
    }

    private boolean allowsExplicitAgent(ChannelIdentity identity) {
        return identity != null && identity.getChannelType() == ChannelType.HTTP;
    }

    private String describe(InboundMessage inbound) {
        if (inbound == null || inbound.getChannelIdentity() == null) {
            return "missing";
        }
        ChannelIdentity identity = inbound.getChannelIdentity();
        return "channelType=" + identity.getChannelType()
                + ", platformAccountId=" + identity.getPlatformAccountId();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
