package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
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
public class ChannelAgentRouter {
    private final ChannelBindingResolver bindingResolver;
    private final AgentResolver agentResolver;

    public EffectiveChannelRoute resolve(InboundMessage inbound) {
        if (inbound != null && !isBlank(inbound.getAgentId()) && allowsExplicitAgent(inbound.getChannelIdentity())) {
            requireAgent(inbound.getAgentId(), "explicit scene override");
            return new EffectiveChannelRoute(inbound.getAgentId(), null, EffectiveChannelRoute.Source.EXPLICIT);
        }

        Optional<ChannelInstanceBinding> configuredBinding = inbound != null
                ? bindingResolver.resolveConfigured(inbound.getChannelIdentity())
                : Optional.empty();
        Optional<ChannelInstanceBinding> binding = configuredBinding.filter(ChannelInstanceBinding::enabled);
        if (binding.isPresent()) {
            ChannelInstanceBinding value = binding.get();
            requireAgent(value.agentId(), "channel binding " + value.bindingId());
            return new EffectiveChannelRoute(value.agentId(), value.bindingId(), EffectiveChannelRoute.Source.BINDING);
        }

        throw new IllegalArgumentException("No agent binding for channel identity: " + describe(inbound));
    }

    private void requireAgent(String agentId, String source) {
        if (isBlank(agentId)) {
            throw new IllegalArgumentException("Missing agent for " + source);
        }
        try {
            AgentDefinition resolved = agentResolver.resolve(agentId);
            if (resolved == null || !agentId.equals(resolved.getAgentId())) {
                throw new IllegalArgumentException("Unknown agent for " + source + ": " + agentId);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown agent for " + source + ": " + agentId, e);
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
