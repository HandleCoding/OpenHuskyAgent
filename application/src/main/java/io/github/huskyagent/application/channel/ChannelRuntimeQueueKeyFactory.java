package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.session.IsolationScope;
import io.github.huskyagent.application.session.SessionKeyStrategy;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.springframework.stereotype.Component;

@Component
public class ChannelRuntimeQueueKeyFactory {

    private final AgentResolver agentResolver;
    private final SessionKeyStrategy sessionKeyStrategy;

    public ChannelRuntimeQueueKeyFactory(AgentResolver agentResolver,
                                         SessionKeyStrategy sessionKeyStrategy) {
        this.agentResolver = agentResolver;
        this.sessionKeyStrategy = sessionKeyStrategy;
    }

    public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
        if (inbound == null) {
            return "channel-session:missing-inbound";
        }
        AgentDefinition agentDefinition = agentResolver.resolve(route.agentId());
        String sessionKey = sessionKeyStrategy.buildKey(IsolationScope.from(
                null,
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                agentDefinition));
        String requestedSessionId = inbound.getRequestedSessionId();
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            return sessionKey + "|requestedSession=" + requestedSessionId;
        }
        return sessionKey;
    }
}