package io.github.huskyagent.service.channel.slack;

import com.slack.api.model.event.MessageEvent;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackInstanceEventHandlerTest {

    @Test
    void normalEventDispatchesToRuntime() {
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        when(runtimeService.handleInboundAsync(any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        SlackInstanceAdapter adapter = adapter();
        SlackInstanceEventHandler handler = new SlackInstanceEventHandler(adapter, runtimeService, Runnable::run, new SlackInboundDeduplicator());

        handler.handleInbound(adapter.normalizeMessage(message("Ev1"), ChannelAuthContext.builder().build(), "Ev1"));

        verify(runtimeService).handleInboundAsync(any(InboundMessage.class), any(), any(Executor.class));
    }

    @Test
    void duplicateEventIsDropped() {
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        when(runtimeService.handleInboundAsync(any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        SlackInstanceAdapter adapter = adapter();
        SlackInstanceEventHandler handler = new SlackInstanceEventHandler(adapter, runtimeService, Runnable::run, new SlackInboundDeduplicator());
        InboundMessage inbound = adapter.normalizeMessage(message("Ev1"), ChannelAuthContext.builder().build(), "Ev1");

        handler.handleInbound(inbound);
        handler.handleInbound(inbound);

        verify(runtimeService).handleInboundAsync(any(InboundMessage.class), any(), any(Executor.class));
    }

    @Test
    void ignoredEventIsNotDispatched() {
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        SlackInstanceEventHandler handler = new SlackInstanceEventHandler(adapter(), runtimeService, Runnable::run, new SlackInboundDeduplicator());

        handler.handleInbound(InboundMessage.ignored("raw"));

        verify(runtimeService, never()).handleInboundAsync(any(), any(), any());
    }

    private SlackInstanceAdapter adapter() {
        SlackProperties.InstanceProperties properties = new SlackProperties.InstanceProperties();
        properties.setBotUserId("U999");
        properties.setMentionRequiredInChannel(false);
        return new SlackInstanceAdapter(properties, new RecordingSlackApiClient(properties), new ToolDisplayMessageRenderer());
    }

    private MessageEvent message(String eventId) {
        MessageEvent event = new MessageEvent();
        event.setChannel("C1");
        event.setUser("U123");
        event.setTs("1710000000.000100");
        event.setEventTs(eventId);
        event.setText("hello");
        event.setChannelType("channel");
        return event;
    }

    private static class RecordingSlackApiClient extends SlackApiClient {
        RecordingSlackApiClient(SlackProperties.InstanceProperties properties) {
            super(properties, null);
        }
    }
}
