package io.github.huskyagent.service.channel.slack;

import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

@Slf4j
public class SlackInstanceEventHandler {

    private final SlackInstanceAdapter adapter;
    private final ChannelRuntimeService runtimeService;
    private final Executor agentExecutor;
    private final SlackInboundDeduplicator deduplicator;

    public SlackInstanceEventHandler(SlackInstanceAdapter adapter,
                                     ChannelRuntimeService runtimeService,
                                     Executor agentExecutor,
                                     SlackInboundDeduplicator deduplicator) {
        this.adapter = adapter;
        this.runtimeService = runtimeService;
        this.agentExecutor = agentExecutor;
        this.deduplicator = deduplicator;
    }

    public Response handleAppMention(EventsApiPayload<AppMentionEvent> payload, EventContext context) {
        String eventId = payload != null ? payload.getEventId() : null;
        AppMentionEvent event = payload != null ? payload.getEvent() : null;
        handleInbound(adapter.normalizeAppMention(event, ChannelAuthContext.builder().build(), eventId));
        return context.ack();
    }

    public Response handleMessage(EventsApiPayload<MessageEvent> payload, EventContext context) {
        String eventId = payload != null ? payload.getEventId() : null;
        MessageEvent event = payload != null ? payload.getEvent() : null;
        handleInbound(adapter.normalizeMessage(event, ChannelAuthContext.builder().build(), eventId));
        return context.ack();
    }

    public Response handleBlockAction(BlockActionRequest request, ActionContext context) {
        String value = request != null && request.getPayload() != null && request.getPayload().getActions() != null
                && !request.getPayload().getActions().isEmpty()
                ? request.getPayload().getActions().get(0).getValue()
                : null;
        String userId = request != null && request.getPayload() != null && request.getPayload().getUser() != null
                ? request.getPayload().getUser().getId()
                : null;
        boolean handled = adapter.completeApproval(value, userId) || adapter.completeClarify(value, userId);
        if (!handled) {
            log.info("Ignored Slack block action: userId={}, value={}", userId, value);
        }
        return context.ack();
    }

    void handleInbound(InboundMessage inbound) {
        if (inbound == null || inbound.isIgnored()) {
            log.info("Ignored Slack inbound event");
            return;
        }
        if (deduplicator.isDuplicate(adapter.platformAccountId(), inbound.getMessageId())) {
            log.info("Dropped duplicate Slack inbound event: platformAccountId={}, messageId={}",
                    adapter.platformAccountId(), inbound.getMessageId());
            return;
        }
        log.info("Dispatching Slack inbound message: principal={}, channelId={}, textLength={}",
                inbound.getPrincipal() != null ? inbound.getPrincipal().getId() : null,
                inbound.getChannelIdentity() != null ? inbound.getChannelIdentity().getChatId() : null,
                inbound.getText() != null ? inbound.getText().length() : 0);
        runtimeService.handleInboundAsync(inbound, adapter, agentExecutor)
                .exceptionally(error -> {
                    log.error("Slack inbound handling failed", error);
                    return null;
                });
    }
}
