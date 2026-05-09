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
            return new EffectiveChannelRoute(inbound.getSceneId(), null, EffectiveChannelRoute.Source.EXPLICIT);
        }

        Optional<ChannelInstanceBinding> configuredBinding = inbound != null
                ? bindingResolver.resolveConfigured(inbound.getChannelIdentity())
                : Optional.empty();
        Optional<ChannelInstanceBinding> binding = configuredBinding.filter(ChannelInstanceBinding::enabled);
        if (binding.isPresent()) {
            ChannelInstanceBinding value = binding.get();
            if (sceneExists(value.sceneId())) {
                return new EffectiveChannelRoute(value.sceneId(), value.bindingId(), EffectiveChannelRoute.Source.BINDING);
            }
            log.warn("Ignoring channel binding with unknown scene: bindingId={}, sceneId={}", value.bindingId(), value.sceneId());
        }

        String legacyScene = legacyDefaultScene(inbound, configuredBinding.isEmpty());
        if (!isBlank(legacyScene)) {
            return new EffectiveChannelRoute(legacyScene, null, EffectiveChannelRoute.Source.CHANNEL_LEGACY_DEFAULT);
        }

        Optional<String> globalDefault = bindingResolver.defaultScene();
        if (globalDefault.isPresent() && sceneExists(globalDefault.get())) {
            return new EffectiveChannelRoute(globalDefault.get(), null, EffectiveChannelRoute.Source.GLOBAL_DEFAULT);
        }
        globalDefault.ifPresent(sceneId -> log.warn("Ignoring channel binding global default with unknown scene: sceneId={}", sceneId));

        return new EffectiveChannelRoute(sceneResolver.resolveDefault().getSceneId(), null, EffectiveChannelRoute.Source.SCENE_DEFAULT);
    }

    private boolean sceneExists(String sceneId) {
        if (isBlank(sceneId)) {
            return false;
        }
        SceneConfig resolved = sceneResolver.resolve(sceneId);
        return resolved != null && sceneId.equals(resolved.getSceneId());
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
