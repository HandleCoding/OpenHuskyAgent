package io.github.huskyagent.application.channel.runtime;

import io.github.huskyagent.application.channel.ChannelAdapter;
import io.github.huskyagent.application.channel.ChannelAdapterRegistry;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.channel.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeEventBridgeTest {

    @Test
    void deliversStartedToolEventToAdapter() {
        CapturingEventBus eventBus = new CapturingEventBus();
        SessionRouteRegistry routeRegistry = new SessionRouteRegistry();
        CapturingAdapter adapter = new CapturingAdapter();
        new RuntimeEventBridge(eventBus, routeRegistry, new ChannelAdapterRegistry(List.of(adapter)));
        routeRegistry.register(route());

        eventBus.subscriber.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_START,
                Map.of(
                        HookDataKeys.TOOL_NAME, "web_search",
                        HookDataKeys.TOOL_ARGS_PREVIEW, "query=test",
                        HookDataKeys.TOOL_CALL_ID, "call-1"
                ),
                Instant.now()
        ));

        assertNotNull(adapter.event);
        ToolDisplayEvent event = (ToolDisplayEvent) adapter.event;
        assertEquals(ToolDisplayStatus.STARTED, event.status());
        assertEquals("web_search", event.toolName());
        assertEquals("query=test", event.argsPreview());
        assertEquals("call-1", event.toolCallId());
    }

    @Test
    void mapsFailedToolAfterEvent() {
        CapturingEventBus eventBus = new CapturingEventBus();
        SessionRouteRegistry routeRegistry = new SessionRouteRegistry();
        CapturingAdapter adapter = new CapturingAdapter();
        new RuntimeEventBridge(eventBus, routeRegistry, new ChannelAdapterRegistry(List.of(adapter)));
        routeRegistry.register(route());

        eventBus.subscriber.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_AFTER,
                Map.of(
                        HookDataKeys.TOOL_NAME, "web_search",
                        HookDataKeys.TOOL_STATUS, "failed",
                        HookDataKeys.TOOL_DURATION_MS, 1016L,
                        HookDataKeys.TOOL_ERROR, "timeout"
                ),
                Instant.now()
        ));

        ToolDisplayEvent event = (ToolDisplayEvent) adapter.event;
        assertEquals(ToolDisplayStatus.FAILED, event.status());
        assertEquals(1016L, event.durationMs());
        assertEquals("timeout", event.error());
    }

    @Test
    void ignoresEventsWithoutActiveRoute() {
        CapturingEventBus eventBus = new CapturingEventBus();
        CapturingAdapter adapter = new CapturingAdapter();
        new RuntimeEventBridge(eventBus, new SessionRouteRegistry(), new ChannelAdapterRegistry(List.of(adapter)));

        eventBus.subscriber.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_START,
                Map.of(HookDataKeys.TOOL_NAME, "web_search"),
                Instant.now()
        ));

        assertNull(adapter.event);
    }

    @Test
    void ignoresAdaptersWithoutRuntimeEventOverride() {
        CapturingEventBus eventBus = new CapturingEventBus();
        SessionRouteRegistry routeRegistry = new SessionRouteRegistry();
        new RuntimeEventBridge(eventBus, routeRegistry, new ChannelAdapterRegistry(List.of(new BasicAdapter())));
        routeRegistry.register(route());

        assertDoesNotThrow(() -> eventBus.subscriber.onEvent(new ChannelEvent(
                "s1",
                HookEvent.TOOL_CALL_START,
                Map.of(HookDataKeys.TOOL_NAME, "web_search"),
                Instant.now()
        )));
    }

    private SessionRoute route() {
        ChannelIdentity identity = ChannelIdentity.builder().channelType(ChannelType.FEISHU).build();
        return new SessionRoute("s1", ChannelType.FEISHU, identity, ReplyTarget.builder().chatId("oc_chat").build(), "om_1");
    }

    private static class CapturingEventBus implements ChannelEventBus {
        ChannelSubscriber subscriber;

        @Override
        public void publish(ChannelEvent event) {}

        @Override
        public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void unsubscribe(String channelName) {}

        @Override
        public void streamToken(String sessionId, String token, boolean reasoning) {}

        @Override
        public void subscribeTokens(String channelName, TokenSubscriber subscriber) {}

        @Override
        public void unsubscribeTokens(String channelName) {}
    }

    private static class CapturingAdapter extends BasicAdapter {
        SessionRoute route;
        RuntimeEvent event;

        @Override
        public void onRuntimeEvent(SessionRoute route, RuntimeEvent event) {
            this.route = route;
            this.event = event;
        }
    }

    private static class BasicAdapter implements ChannelAdapter {
        @Override
        public ChannelType channelType() {
            return ChannelType.FEISHU;
        }

        @Override
        public ChannelCapabilities capabilities() {
            return ChannelCapabilities.basic();
        }

        @Override
        public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
            return null;
        }

        @Override
        public void send(OutboundMessage message) {}

        @Override
        public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            return ApprovalDecision.deny("unsupported");
        }

        @Override
        public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
            return ClarifyDecision.answer("");
        }
    }
}