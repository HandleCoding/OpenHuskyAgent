package io.github.huskyagent.domain.hook;

import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class DefaultHookRegistry implements HookRegistry {

    private final Map<String, AgentHook> hooksByName = new ConcurrentHashMap<>();
    private final Map<HookEvent, List<AgentHook>> hooksByEvent = new ConcurrentHashMap<>();
    private final ChannelEventBus channelEventBus;

    public DefaultHookRegistry(List<AgentHook> hookBeans, ChannelEventBus channelEventBus) {
        this.channelEventBus = channelEventBus;
        for (AgentHook hook : hookBeans) {
            register(hook);
        }
        log.info("HookRegistry initialized: {} hooks registered", hooksByName.size());
        for (Map.Entry<HookEvent, List<AgentHook>> entry : hooksByEvent.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                log.debug("  {}: {}", entry.getKey(),
                        entry.getValue().stream().map(AgentHook::name).toList());
            }
        }
    }

    @Override
    public void register(AgentHook hook) {
        AgentHook existing = hooksByName.put(hook.name(), hook);
        if (existing != null) {
            // Remove old hook from all event lists
            for (List<AgentHook> list : hooksByEvent.values()) {
                list.removeIf(h -> h.name().equals(hook.name()));
            }
            log.warn("Hook '{}' replaced existing hook of same name", hook.name());
        }
        for (HookEvent event : hook.supportedEvents()) {
            hooksByEvent.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(hook);
        }
        sortByOrder();
    }

    @Override
    public void unregister(String hookName) {
        AgentHook removed = hooksByName.remove(hookName);
        if (removed != null) {
            for (List<AgentHook> list : hooksByEvent.values()) {
                list.removeIf(h -> h.name().equals(hookName));
            }
        }
    }

    @Override
    public HookResult fireBefore(HookEvent event, String sessionId, Map<String, Object> data) {
        List<AgentHook> hooks = hooksByEvent.getOrDefault(event, List.of());
        Map<String, Object> accumulatedMods = new HashMap<>();

        for (AgentHook hook : hooks) {
            if (!(hook instanceof BeforeHook bh)) continue;
            try {
                HookContext ctx = accumulatedMods.isEmpty()
                        ? new HookContext(event, sessionId, data)
                        : new HookContext(event, sessionId, merge(data, accumulatedMods));
                HookResult result = bh.before(ctx);
                if (!result.allowed()) {
                    log.info("Hook '{}' blocked {}: {}", hook.name(), event, result.blockReason());
                    return result;
                }
                if (result.hasModifications()) {
                    accumulatedMods.putAll(result.modifications());
                }
            } catch (Exception e) {
                log.error("Hook '{}' threw exception in fireBefore({}): {}",
                        hook.name(), event, e.getMessage(), e);
            }
        }

        if (!accumulatedMods.isEmpty()) {
            return HookResult.allowWith(accumulatedMods);
        }
        return HookResult.allow();
    }

    @Override
    public void fireAfter(HookEvent event, String sessionId, Map<String, Object> data) {
        List<AgentHook> hooks = hooksByEvent.getOrDefault(event, List.of());
        for (AgentHook hook : hooks) {
            if (!(hook instanceof AfterHook ah)) continue;
            try {
                ah.after(new HookContext(event, sessionId, data));
            } catch (Exception e) {
                log.error("Hook '{}' threw exception in fireAfter({}): {}",
                        hook.name(), event, e.getMessage(), e);
            }
        }

        channelEventBus.publish(new ChannelEvent(sessionId, event, data, Instant.now()));
    }

    @Override
    public List<AgentHook> getHooks() {
        return List.copyOf(hooksByName.values());
    }

    @Override
    public List<AgentHook> getHooks(HookEvent event) {
        return List.copyOf(hooksByEvent.getOrDefault(event, List.of()));
    }

    private void sortByOrder() {
        for (List<AgentHook> list : hooksByEvent.values()) {
            list.sort(Comparator.comparingInt(AgentHook::order));
        }
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> result = new HashMap<>(base);
        result.putAll(overlay);
        return result;
    }
}
