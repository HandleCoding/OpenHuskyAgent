package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.session.IsolationScope;
import io.github.huskyagent.application.session.SessionKeyStrategy;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.domain.scene.SceneResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.springframework.stereotype.Component;

@Component
public class ChannelRuntimeQueueKeyFactory {

    private final SceneResolver sceneResolver;
    private final SessionKeyStrategy sessionKeyStrategy;

    public ChannelRuntimeQueueKeyFactory(SceneResolver sceneResolver,
                                         SessionKeyStrategy sessionKeyStrategy) {
        this.sceneResolver = sceneResolver;
        this.sessionKeyStrategy = sessionKeyStrategy;
    }

    public String keyFor(InboundMessage inbound, EffectiveChannelRoute route) {
        if (inbound == null) {
            return "channel-session:missing-inbound";
        }
        SceneConfig sceneConfig = sceneResolver.resolve(route.sceneId());
        String sessionKey = sessionKeyStrategy.buildKey(IsolationScope.from(
                null,
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                sceneConfig));
        String requestedSessionId = inbound.getRequestedSessionId();
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            return sessionKey + "|requestedSession=" + requestedSessionId;
        }
        return sessionKey;
    }
}