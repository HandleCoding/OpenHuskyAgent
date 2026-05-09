package io.github.huskyagent.application.channel.runtime;

import io.github.huskyagent.application.channel.ChannelAdapter;
import io.github.huskyagent.application.channel.ChannelAdapterRegistry;
import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class RuntimeEventBridge implements ChannelSubscriber {

    private static final Set<HookEvent> SUPPORTED_EVENTS = Set.of(
            HookEvent.TOOL_CALL_START,
            HookEvent.TOOL_CALL_AFTER
    );

    private final SessionRouteRegistry routeRegistry;
    private final ChannelAdapterRegistry adapterRegistry;

    public RuntimeEventBridge(ChannelEventBus eventBus,
                              SessionRouteRegistry routeRegistry,
                              ChannelAdapterRegistry adapterRegistry) {
        this.routeRegistry = routeRegistry;
        this.adapterRegistry = adapterRegistry;
        eventBus.subscribe("channel-runtime", SUPPORTED_EVENTS, this);
    }

    @Override
    public void onEvent(ChannelEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        SessionRoute route = routeRegistry.find(event.sessionId()).orElse(null);
        if (route == null) {
            log.debug("No session route for sessionId={}, skipping {}", event.sessionId(), event.type());
            return;
        }
        RuntimeEvent runtimeEvent = toRuntimeEvent(event);
        if (runtimeEvent == null) {
            return;
        }
        ChannelAdapter adapter = adapterRegistry.find(route.channelType()).orElse(null);
        if (adapter == null) {
            log.debug("No adapter for channel {}", route.channelType());
            return;
        }
        adapter.onRuntimeEvent(route, runtimeEvent);
    }

    private RuntimeEvent toRuntimeEvent(ChannelEvent event) {
        return switch (event.type()) {
            case TOOL_CALL_START -> toToolEvent(event, ToolDisplayStatus.STARTED);
            case TOOL_CALL_AFTER -> toToolEvent(event, afterStatus(event.data()));
            default -> null;
        };
    }

    private ToolDisplayStatus afterStatus(Map<String, Object> data) {
        String status = stringValue(data, HookDataKeys.TOOL_STATUS);
        return "failed".equals(status) ? ToolDisplayStatus.FAILED : ToolDisplayStatus.COMPLETED;
    }

    private ToolDisplayEvent toToolEvent(ChannelEvent event, ToolDisplayStatus status) {
        Map<String, Object> data = event.data();
        return new ToolDisplayEvent(
                event.sessionId(),
                stringValue(data, HookDataKeys.TOOL_CALL_ID),
                stringValue(data, HookDataKeys.TOOL_NAME),
                stringValue(data, HookDataKeys.TOOL_ARGS_PREVIEW),
                stringValue(data, HookDataKeys.TOOL_ARGS),
                status,
                longValue(data, HookDataKeys.TOOL_DURATION_MS),
                stringValue(data, HookDataKeys.TOOL_ERROR),
                event.timestamp() != null ? event.timestamp() : Instant.now()
        );
    }

    private String stringValue(Map<String, Object> data, String key) {
        Object value = data != null ? data.get(key) : null;
        return value != null ? String.valueOf(value) : null;
    }

    private long longValue(Map<String, Object> data, String key) {
        Object value = data != null ? data.get(key) : null;
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}