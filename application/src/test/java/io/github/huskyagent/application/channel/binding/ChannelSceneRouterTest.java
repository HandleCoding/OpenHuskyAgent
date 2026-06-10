package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChannelSceneRouterTest {

    @Test
    void bindingRoutesInboundToAgent() {
        ChannelSceneRouter router = router(binding("assistant-binding", ChannelType.FEISHU, "cli_assistant", "assistant"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals("assistant-binding", route.bindingId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void bindingBeatsNonHttpExplicitScene() {
        ChannelSceneRouter router = router(binding("assistant-binding", ChannelType.FEISHU, "cli_assistant", "assistant"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", "support");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void httpExplicitAgentIsAllowed() {
        ChannelSceneRouter router = router(binding("http-binding", ChannelType.HTTP, "chatbot", "chatbot"));
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", "assistant");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.EXPLICIT, route.source());
    }

    @Test
    void httpBindingAppliesWithoutExplicitAgent() {
        ChannelSceneRouter router = router(binding("http-binding", ChannelType.HTTP, "chatbot", "chatbot"));
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("chatbot", route.sceneId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void unboundInboundFailsClosed() {
        ChannelSceneRouter router = router(Optional.empty());
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_unknown", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve(inbound));

        assertTrue(error.getMessage().contains("No agent binding"));
    }

    @Test
    void unknownBindingAgentFailsClosed() {
        ChannelSceneRouter router = router(binding("bad-binding", ChannelType.FEISHU, "cli_assistant", "missing-agent"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve(inbound));

        assertTrue(error.getMessage().contains("missing-agent"));
    }

    private ChannelSceneRouter router(Optional<ChannelInstanceBinding> binding) {
        ChannelBindingResolver bindingResolver = new ChannelBindingResolver() {
            @Override
            public Optional<ChannelInstanceBinding> resolve(ChannelIdentity identity) {
                return resolveConfigured(identity).filter(ChannelInstanceBinding::enabled);
            }

            @Override
            public Optional<ChannelInstanceBinding> resolveConfigured(ChannelIdentity identity) {
                if (identity == null || binding.isEmpty()) {
                    return Optional.empty();
                }
                ChannelInstanceBinding value = binding.get();
                return value.channelType() == identity.getChannelType()
                        && value.platformAccountId().equals(identity.getPlatformAccountId())
                        ? binding
                        : Optional.empty();
            }
        };
        SceneResolver sceneResolver = new SceneResolver() {
            @Override
            public SceneConfig resolve(String sceneId) {
                if (sceneId == null || "missing-agent".equals(sceneId)) {
                    throw new IllegalArgumentException("Unknown agent: " + sceneId);
                }
                SceneConfig config = new SceneConfig();
                config.setSceneId(sceneId);
                return config;
            }

            @Override
            public SceneConfig resolveDefault() {
                throw new UnsupportedOperationException("No default agent in final routing model");
            }
        };
        return new ChannelSceneRouter(bindingResolver, sceneResolver);
    }

    private Optional<ChannelInstanceBinding> binding(String bindingId, ChannelType channelType,
                                                     String platformAccountId, String agentId) {
        return Optional.of(new ChannelInstanceBinding(bindingId, channelType, platformAccountId, agentId, true, null, null));
    }

    private InboundMessage inbound(ChannelType channelType, String platformAccountId, String agentId) {
        return InboundMessage.builder()
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(channelType)
                        .platformAccountId(platformAccountId)
                        .build())
                .sceneId(agentId)
                .build();
    }
}
