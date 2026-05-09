package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.agent.TextEvent;
import io.github.huskyagent.application.runtime.RuntimeCallbacks;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;

import java.util.Map;
import java.util.UUID;

class AdapterCallbacks implements RuntimeCallbacks {

    private final ChannelAdapter adapter;
    private final InboundMessage inbound;

    AdapterCallbacks(ChannelAdapter adapter, InboundMessage inbound) {
        this.adapter = adapter;
        this.inbound = inbound;
    }

    @Override
    public void started(RuntimeScope scope) {
        sendStatus(scope, "started");
    }

    @Override
    public void text(RuntimeScope scope, TextEvent event) {
        if (!adapter.capabilities().isSupportsStreaming() || event == null || !event.isTokenEvent()) {
            return;
        }
        adapter.send(OutboundMessage.builder()
                .kind(event.reasoning() ? OutboundMessage.Kind.REASONING : OutboundMessage.Kind.TOKEN)
                .sessionId(scope.getSessionId())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .text(event.token())
                .build());
    }

    @Override
    public void approval(RuntimeScope scope, ApprovalContext approval) {
        ApprovalPrompt prompt = ApprovalPrompt.builder()
                .requestId(UUID.randomUUID().toString())
                .sessionId(approval.sessionId())
                .toolName(approval.toolName())
                .toolArgs(approval.toolArgs())
                .reason(approval.reason())
                .agentText(approval.agentText())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .build();
        ApprovalDecision decision = adapter.requestApproval(prompt);
        approval.approve(decision.isApproved(), decision.isAlways());
    }

    @Override
    public void clarify(RuntimeScope scope, ClarifyContext clarify) {
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId(UUID.randomUUID().toString())
                .sessionId(clarify.sessionId())
                .question(clarify.question())
                .options(clarify.options())
                .agentText(clarify.agentText())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .build();
        ClarifyDecision decision = adapter.requestClarify(prompt);
        clarify.respond(decision.getAnswer());
    }

    @Override
    public void completed(RuntimeScope scope, ChatResult result) {
        sendStatus(scope, "completed");
        adapter.send(OutboundMessage.builder()
                .kind(OutboundMessage.Kind.DONE)
                .sessionId(scope.getSessionId())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .text(result.content())
                .metadata(Map.of("streamed", result.streamed()))
                .build());
    }

    @Override
    public void failed(RuntimeScope scope, String errorMessage) {
        sendStatus(scope, "failed");
        adapter.send(OutboundMessage.builder()
                .kind(OutboundMessage.Kind.ERROR)
                .sessionId(scope.getSessionId())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .text(errorMessage != null ? errorMessage : "Unknown error")
                .build());
    }

    private void sendStatus(RuntimeScope scope, String status) {
        adapter.typing(OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TOOL_STATUS)
                .sessionId(scope.getSessionId())
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .metadata(Map.of("status", status))
                .build());
    }
}