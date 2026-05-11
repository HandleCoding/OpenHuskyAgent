package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
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
    private static final String HTTP_LEGACY_SCENE = "chatbot";

    private final ChannelBindingResolver bindingResolver;
    private final SceneResolver sceneResolver;

    public EffectiveChannelRoute resolve(InboundMessage inbound) {
        if (inbound != null && !isBlank(inbound.getSceneId()) && bindingResolver.allowsExplicitSceneOverride(inbound.getChannelIdentity())) {
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

        String legacyScene = legacyDefaultScene(inbound, configuredBinding.isEmpty());
        if (!isBlank(legacyScene)) {
            requireScene(legacyScene, "legacy channel default");
            return new EffectiveChannelRoute(legacyScene, null, EffectiveChannelRoute.Source.CHANNEL_LEGACY_DEFAULT);
        }

        Optional<String> globalDefault = bindingResolver.defaultScene();
        if (globalDefault.isPresent()) {
            requireScene(globalDefault.get(), "channel binding global default");
            return new EffectiveChannelRoute(globalDefault.get(), null, EffectiveChannelRoute.Source.GLOBAL_DEFAULT);
        }

        return new EffectiveChannelRoute(sceneResolver.resolveDefault().getSceneId(), null, EffectiveChannelRoute.Source.SCENE_DEFAULT);
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

    private String legacyDefaultScene(InboundMessage inbound, boolean noConfiguredBinding) {
        if (inbound == null || inbound.getChannelIdentity() == null) {
            return null;
        }
        ChannelType channelType = inbound.getChannelIdentity().getChannelType();
        if (channelType == ChannelType.FEISHU) {
            log.warn("Legacy scene resolution for Feishu: sceneId='{}'. Consider configuring channel-bindings instead of instance defaultScene.",
                    inbound.getSceneId());
            return inbound.getSceneId();
        }
        if (channelType == ChannelType.HTTP && noConfiguredBinding) {
            log.warn("Legacy scene resolution for HTTP: sceneId='{}'. Consider configuring channel-bindings.default-scene or bindings instead.",
                    HTTP_LEGACY_SCENE);
            return HTTP_LEGACY_SCENE;
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
