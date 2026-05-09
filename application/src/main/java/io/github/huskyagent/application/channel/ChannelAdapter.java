package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.channel.runtime.RuntimeEvent;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelCapabilities;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;

public interface ChannelAdapter {

    ChannelType channelType();

    ChannelCapabilities capabilities();

    InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext);

    void send(OutboundMessage message);

    default void edit(OutboundMessage message) {
        send(message);
    }

    default void typing(OutboundMessage message) {
    }

    ApprovalDecision requestApproval(ApprovalPrompt prompt);

    ClarifyDecision requestClarify(ClarifyPrompt prompt);

    default void onRuntimeEvent(SessionRoute route, RuntimeEvent event) {
    }
}
