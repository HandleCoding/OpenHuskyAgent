package io.github.huskyagent.service.channel.feishu;

import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuInstanceEventHandlerTest {

    @Test
    void duplicateMessageIdIsDroppedBeforeRuntime() {
        FeishuInstanceAdapter adapter = mock(FeishuInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        FeishuInstanceEventHandler handler = handler(adapter, runtimeService, new FeishuInboundDeduplicator());
        P2MessageReceiveV1 rawEvent = new P2MessageReceiveV1();
        when(adapter.normalizeInbound(any(), any())).thenReturn(inbound("om_1"));
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleMessageEvent(rawEvent, Map.of(), null);
        handler.handleMessageEvent(rawEvent, Map.of(), null);

        verify(runtimeService, times(1)).handleInboundAsync(any(), any(), any());
    }

    @Test
    void distinctMessageIdsAreDispatched() {
        FeishuInstanceAdapter adapter = mock(FeishuInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        FeishuInstanceEventHandler handler = handler(adapter, runtimeService, new FeishuInboundDeduplicator());
        P2MessageReceiveV1 first = new P2MessageReceiveV1();
        P2MessageReceiveV1 second = new P2MessageReceiveV1();
        when(adapter.normalizeInbound(first, authContext())).thenReturn(inbound("om_1"));
        when(adapter.normalizeInbound(second, authContext())).thenReturn(inbound("om_2"));
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleMessageEvent(first, Map.of(), null);
        handler.handleMessageEvent(second, Map.of(), null);

        verify(runtimeService, times(2)).handleInboundAsync(any(), any(), any());
    }

    @Test
    void ignoredMessagesDoNotEnterDedupCache() {
        FeishuInstanceAdapter adapter = mock(FeishuInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        FeishuInboundDeduplicator deduplicator = new FeishuInboundDeduplicator();
        FeishuInstanceEventHandler handler = handler(adapter, runtimeService, deduplicator);
        P2MessageReceiveV1 ignored = new P2MessageReceiveV1();
        P2MessageReceiveV1 accepted = new P2MessageReceiveV1();
        when(adapter.normalizeInbound(any(), any())).thenAnswer(invocation -> {
            Object event = invocation.getArgument(0);
            if (event == ignored) {
                return InboundMessage.ignored(ignored);
            }
            return inbound("om_1");
        });
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleMessageEvent(ignored, Map.of(), null);
        handler.handleMessageEvent(accepted, Map.of(), null);

        assertEquals(1, deduplicator.size());
        verify(runtimeService, times(1)).handleInboundAsync(any(), any(), any());
    }

    @Test
    void missingMessageIdIsNotDeduped() {
        FeishuInstanceAdapter adapter = mock(FeishuInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        FeishuInstanceEventHandler handler = handler(adapter, runtimeService, new FeishuInboundDeduplicator());
        P2MessageReceiveV1 rawEvent = new P2MessageReceiveV1();
        when(adapter.normalizeInbound(any(), any())).thenReturn(inbound(null));
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleMessageEvent(rawEvent, Map.of(), null);
        handler.handleMessageEvent(rawEvent, Map.of(), null);

        verify(runtimeService, times(2)).handleInboundAsync(any(), any(), any());
    }

    @Test
    void duplicateWindowExpiresAfterTwoHours() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-05T00:00:00Z"));
        FeishuInboundDeduplicator deduplicator = new FeishuInboundDeduplicator(clock);

        assertFalse(deduplicator.isDuplicate("cli_a", "om_1"));
        assertTrue(deduplicator.isDuplicate("cli_a", "om_1"));

        clock.now = clock.now.plus(FeishuInboundDeduplicator.TTL);

        assertFalse(deduplicator.isDuplicate("cli_a", "om_1"));
    }

    @Test
    void platformAccountScopesMessageIds() {
        FeishuInboundDeduplicator deduplicator = new FeishuInboundDeduplicator();

        assertFalse(deduplicator.isDuplicate("cli_a", "om_1"));
        assertFalse(deduplicator.isDuplicate("cli_b", "om_1"));
        assertTrue(deduplicator.isDuplicate("cli_a", "om_1"));
    }

    @Test
    void staleApprovalRequestIsNotCompletedTwice() {
        FeishuInstanceAdapter adapter = mock(FeishuInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        FeishuInstanceEventHandler handler = handler(adapter, runtimeService, new FeishuInboundDeduplicator());
        com.lark.oapi.event.cardcallback.model.P2CardActionTrigger cardEvent = cardEvent("husky_approval", "approval-1", "approve", null);
        when(adapter.completeApproval("approval-1", "approve")).thenReturn(true, false);

        handler.handleInteractiveCard(cardEvent);
        handler.handleInteractiveCard(cardEvent);

        verify(adapter, times(2)).completeApproval("approval-1", "approve");
        verify(runtimeService, never()).handleInboundAsync(any(), any(), any());
    }

    private FeishuInstanceEventHandler handler(FeishuInstanceAdapter adapter,
                                               ChannelRuntimeService runtimeService,
                                               FeishuInboundDeduplicator deduplicator) {
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setAppId("cli_test");
        return new FeishuInstanceEventHandler(properties, adapter, runtimeService, Runnable::run, deduplicator);
    }

    private InboundMessage inbound(String messageId) {
        return InboundMessage.builder()
                .messageId(messageId)
                .text("hello")
                .principal(Principal.builder().id("feishu:cli_test:ou_user").channelType(ChannelType.FEISHU).build())
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.FEISHU)
                        .platformAccountId("cli_test")
                        .chatId("oc_chat")
                        .senderId("ou_user")
                        .build())
                .sceneId("feishu-qa")
                .build();
    }

    private ChannelAuthContext authContext() {
        return ChannelAuthContext.builder().headers(Map.of()).build();
    }

    private com.lark.oapi.event.cardcallback.model.P2CardActionTrigger cardEvent(String kind, String requestId, String decision, String answer) {
        Map<String, Object> value = new java.util.HashMap<>();
        value.put("kind", kind);
        value.put("requestId", requestId);
        if (decision != null) {
            value.put("decision", decision);
        }
        if (answer != null) {
            value.put("answer", answer);
        }
        com.lark.oapi.event.cardcallback.model.P2CardActionTrigger event = new com.lark.oapi.event.cardcallback.model.P2CardActionTrigger();
        com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData eventBody = new com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData();
        com.lark.oapi.event.cardcallback.model.CallBackAction action = new com.lark.oapi.event.cardcallback.model.CallBackAction();
        action.setValue(value);
        eventBody.setAction(action);
        event.setEvent(eventBody);
        return event;
    }

    private static class MutableClock extends Clock {
        Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}