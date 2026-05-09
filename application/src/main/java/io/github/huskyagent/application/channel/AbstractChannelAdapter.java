package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelCapabilities;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractChannelAdapter implements ChannelAdapter {

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.basic();
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
        log.warn("Channel {} does not support approval UI; denying tool {} for session {}",
                channelType().getName(), prompt.getToolName(), prompt.getSessionId());
        return ApprovalDecision.deny("Channel does not support approval");
    }

    @Override
    public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
        log.warn("Channel {} does not support clarify UI; returning empty answer for session {}",
                channelType().getName(), prompt.getSessionId());
        return ClarifyDecision.answer("");
    }
}
