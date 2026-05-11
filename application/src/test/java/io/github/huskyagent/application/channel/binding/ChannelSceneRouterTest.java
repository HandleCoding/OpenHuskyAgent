package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChannelSceneRouterTest {

    @Test
    void feishuBindingBeatsLegacyDefaultScene() {
        ChannelSceneRouter router = router(binding("feishu-binding", "assistant"), Optional.empty(), "default-scene");
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", "feishu-qa");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals("feishu-binding", route.bindingId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void feishuLegacyDefaultAppliesWithoutBinding() {
        ChannelSceneRouter router = router(Optional.empty(), Optional.of("global"), "scene-default");
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_unknown", "feishu-qa");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("feishu-qa", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.CHANNEL_LEGACY_DEFAULT, route.source());
    }

    @Test
    void httpExplicitSceneBeatsBindingWhenOverrideAllowed() {
        ChannelSceneRouter router = router(binding("http-binding", "chatbot"), Optional.empty(), "scene-default", Set.of(ChannelType.HTTP));
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", "assistant");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.EXPLICIT, route.source());
    }

    @Test
    void httpBindingBeatsExplicitSceneWhenOverrideDisabled() {
        ChannelSceneRouter router = router(binding("http-binding", "chatbot"), Optional.empty(), "scene-default", Set.of());
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", "assistant");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("chatbot", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void httpBindingAppliesWithoutExplicitScene() {
        ChannelSceneRouter router = router(binding("http-binding", "support"), Optional.empty(), "scene-default");
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("support", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void httpLegacyFallbackRemainsChatbot() {
        ChannelSceneRouter router = router(Optional.empty(), Optional.of("assistant"), "scene-default");
        InboundMessage inbound = inbound(ChannelType.HTTP, "unknown", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("chatbot", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.CHANNEL_LEGACY_DEFAULT, route.source());
    }

    @Test
    void httpDisabledBindingDoesNotFallbackToLegacyChatbotScene() {
        ChannelSceneRouter router = router(disabledBinding("http-binding", ChannelType.HTTP, "chatbot", "support"), Optional.of("assistant"), "scene-default");
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.GLOBAL_DEFAULT, route.source());
    }

    @Test
    void httpLegacyFallbackOnlyAppliesWhenNoBindingIsConfigured() {
        ChannelSceneRouter router = router(Optional.empty(), Optional.of("assistant"), "scene-default");
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("chatbot", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.CHANNEL_LEGACY_DEFAULT, route.source());
    }

    @Test
    void globalDefaultAppliesBeforeSceneDefault() {
        ChannelSceneRouter router = router(Optional.empty(), Optional.of("assistant"), "scene-default");
        InboundMessage inbound = inbound(ChannelType.TUI, "local", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.GLOBAL_DEFAULT, route.source());
    }

    @Test
    void unknownBindingSceneFailsClosed() {
        ChannelSceneRouter router = router(binding("bad-binding", "missing-scene"), Optional.empty(), "scene-default");
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", "feishu-qa");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve(inbound));

        assertTrue(error.getMessage().contains("missing-scene"));
    }

    private ChannelSceneRouter router(Optional<ChannelInstanceBinding> binding,
                                      Optional<String> globalDefault,
                                      String sceneDefault) {
        return router(binding, globalDefault, sceneDefault, Set.of(ChannelType.HTTP));
    }

    private ChannelSceneRouter router(Optional<ChannelInstanceBinding> binding,
                                      Optional<String> globalDefault,
                                      String sceneDefault,
                                      Set<ChannelType> explicitOverrideTypes) {
        ChannelBindingResolver bindingResolver = new ChannelBindingResolver() {
            @Override
            public Optional<ChannelInstanceBinding> resolve(ChannelIdentity identity) {
                return binding.filter(ChannelInstanceBinding::enabled);
            }

            @Override
            public Optional<ChannelInstanceBinding> resolveConfigured(ChannelIdentity identity) {
                return binding;
            }

            @Override
            public Optional<String> defaultScene() {
                return globalDefault;
            }

            @Override
            public boolean allowsExplicitSceneOverride(ChannelIdentity identity) {
                return identity != null && explicitOverrideTypes.contains(identity.getChannelType());
            }
        };
        SceneResolver sceneResolver = new SceneResolver() {
            @Override
            public SceneConfig resolve(String sceneId) {
                if (sceneId == null || "missing-scene".equals(sceneId)) {
                    throw new IllegalArgumentException("Unknown scene: " + sceneId);
                }
                SceneConfig config = new SceneConfig();
                config.setSceneId(sceneId);
                return config;
            }

            @Override
            public SceneConfig resolveDefault() {
                SceneConfig config = new SceneConfig();
                config.setSceneId(sceneDefault);
                return config;
            }
        };
        return new ChannelSceneRouter(bindingResolver, sceneResolver);
    }

    private Optional<ChannelInstanceBinding> binding(String bindingId, String sceneId) {
        return Optional.of(new ChannelInstanceBinding(bindingId, ChannelType.FEISHU, "account", sceneId, true, null, null));
    }

    private Optional<ChannelInstanceBinding> disabledBinding(String bindingId, ChannelType channelType,
                                                             String platformAccountId, String sceneId) {
        return Optional.of(new ChannelInstanceBinding(bindingId, channelType, platformAccountId, sceneId, false, null, null));
    }

    private InboundMessage inbound(ChannelType channelType, String platformAccountId, String sceneId) {
        return InboundMessage.builder()
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(channelType)
                        .platformAccountId(platformAccountId)
                        .build())
                .sceneId(sceneId)
                .build();
    }
}
