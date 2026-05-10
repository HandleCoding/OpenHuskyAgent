package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

@Value
@Builder
public class RuntimeExecutionRequest {
    InboundMessage inbound;
    EffectiveChannelRoute effectiveRoute;
    String message;
    String requestedSessionId;
    Path workingDirectoryOverride;
    boolean forceNewSession;
    RuntimeCallbacks callbacks;
    PersistenceMode persistenceMode;

    public RuntimeCallbacks callbacksOrNoop() {
        return callbacks != null ? callbacks : RuntimeCallbacks.NOOP;
    }

    public PersistenceMode persistenceModeOrDefault() {
        return persistenceMode != null ? persistenceMode : PersistenceMode.STATEFUL;
    }

    public boolean isStateless() {
        return persistenceModeOrDefault() == PersistenceMode.STATELESS;
    }

    public String requestedSessionIdOrInbound() {
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            return requestedSessionId;
        }
        return inbound != null ? inbound.getRequestedSessionId() : null;
    }

    public String messageOrInboundText() {
        if (message != null && !message.isBlank()) {
            return message;
        }
        return inbound != null ? inbound.getText() : null;
    }

    public enum PersistenceMode {
        STATEFUL,
        STATELESS
    }
}
