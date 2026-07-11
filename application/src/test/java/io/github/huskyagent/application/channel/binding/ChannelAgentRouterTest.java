package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChannelAgentRouterTest {

    @Test
    void bindingRoutesInboundToAgent() {
        ChannelAgentRouter router = router(binding("assistant-binding", ChannelType.FEISHU, "cli_assistant", "assistant"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.agentId());
        assertEquals("assistant-binding", route.bindingId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void bindingBeatsNonHttpExplicitScene() {
        ChannelAgentRouter router = router(binding("assistant-binding", ChannelType.FEISHU, "cli_assistant", "assistant"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", "support");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.agentId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void httpExplicitAgentIsAllowed() {
        ChannelAgentRouter router = router(binding("http-binding", ChannelType.HTTP, "chatbot", "chatbot"));
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", "assistant");

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("assistant", route.agentId());
        assertEquals(EffectiveChannelRoute.Source.EXPLICIT, route.source());
    }

    @Test
    void httpBindingAppliesWithoutExplicitAgent() {
        ChannelAgentRouter router = router(binding("http-binding", ChannelType.HTTP, "chatbot", "chatbot"));
        InboundMessage inbound = inbound(ChannelType.HTTP, "chatbot", null);

        EffectiveChannelRoute route = router.resolve(inbound);

        assertEquals("chatbot", route.agentId());
        assertEquals(EffectiveChannelRoute.Source.BINDING, route.source());
    }

    @Test
    void unboundInboundFailsClosed() {
        ChannelAgentRouter router = router(Optional.empty());
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_unknown", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve(inbound));

        assertTrue(error.getMessage().contains("No agent binding"));
    }

    @Test
    void unknownBindingAgentFailsClosed() {
        ChannelAgentRouter router = router(binding("bad-binding", ChannelType.FEISHU, "cli_assistant", "missing-agent"));
        InboundMessage inbound = inbound(ChannelType.FEISHU, "cli_assistant", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> router.resolve(inbound));

        assertTrue(error.getMessage().contains("missing-agent"));
    }

    private ChannelAgentRouter router(Optional<ChannelInstanceBinding> binding) {
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
        AgentResolver agentResolver = new AgentResolver() {
            @Override
            public AgentDefinition resolve(String agentId) {
                if (agentId == null || "missing-agent".equals(agentId)) {
                    throw new IllegalArgumentException("Unknown agent: " + agentId);
                }
                AgentDefinition config = new AgentDefinition();
                config.setAgentId(agentId);
                return config;
            }

            @Override
            public AgentDefinition resolveDefault() {
                throw new UnsupportedOperationException("No default agent in final routing model");
            }
        };
        return new ChannelAgentRouter(bindingResolver, agentResolver);
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
                .agentId(agentId)
                .build();
    }
}
