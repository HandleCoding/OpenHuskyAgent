package io.github.huskyagent.domain.hook;

import io.github.huskyagent.domain.event.ChannelEvent;
import io.github.huskyagent.domain.event.ChannelEventBus;
import io.github.huskyagent.domain.event.ChannelSubscriber;
import io.github.huskyagent.domain.event.TokenSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHookRegistryTest {

    private static final ChannelEventBus NOOP_EVENT_BUS = new NoopChannelEventBus();

    private DefaultHookRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultHookRegistry(List.of(), NOOP_EVENT_BUS);
    }


    @Test
    void registerAndDiscover() {
        AgentHook hook = new StubAfterHook("test-hook", Set.of(HookEvent.SESSION_START));
        registry.register(hook);

        assertEquals(1, registry.getHooks().size());
        assertEquals(1, registry.getHooks(HookEvent.SESSION_START).size());
        assertEquals(0, registry.getHooks(HookEvent.SESSION_END).size());
    }

    @Test
    void unregister() {
        registry.register(new StubAfterHook("a", Set.of(HookEvent.SESSION_START)));
        registry.register(new StubAfterHook("b", Set.of(HookEvent.SESSION_START)));
        registry.unregister("a");

        assertEquals(1, registry.getHooks().size());
        assertEquals("b", registry.getHooks().get(0).name());
    }

    @Test
    void duplicateNameReplaces() {
        registry.register(new StubAfterHook("x", Set.of(HookEvent.SESSION_START)));
        registry.register(new StubAfterHook("x", Set.of(HookEvent.SESSION_END)));

        assertEquals(1, registry.getHooks().size());
        assertEquals(1, registry.getHooks(HookEvent.SESSION_END).size());
        assertEquals(0, registry.getHooks(HookEvent.SESSION_START).size());
    }

    @Test
    void autoDiscoveryViaConstructor() {
        AgentHook h1 = new StubAfterHook("h1", Set.of(HookEvent.TOOL_CALL_AFTER));
        AgentHook h2 = new StubAfterHook("h2", Set.of(HookEvent.TOOL_CALL_AFTER, HookEvent.LLM_CALL_AFTER));

        DefaultHookRegistry auto = new DefaultHookRegistry(List.of(h1, h2), NOOP_EVENT_BUS);
        assertEquals(2, auto.getHooks().size());
        assertEquals(2, auto.getHooks(HookEvent.TOOL_CALL_AFTER).size());
        assertEquals(1, auto.getHooks(HookEvent.LLM_CALL_AFTER).size());
    }

    // ── fireBefore ──────────────────────────────────────────────────────────────

    @Test
    void fireBefore_AllAllow() {
        registry.register(new StubBeforeHook("h1", Set.of(HookEvent.TOOL_CALL_BEFORE), HookResult.allow()));
        registry.register(new StubBeforeHook("h2", Set.of(HookEvent.TOOL_CALL_BEFORE), HookResult.allow()));

        HookResult result = registry.fireBefore(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        assertTrue(result.allowed());
    }

    @Test
    void fireBefore_FirstBlockWins() {
        registry.register(new StubBeforeHook("blocker", Set.of(HookEvent.TOOL_CALL_BEFORE),
                HookResult.block("circuit open")));
        registry.register(new StubBeforeHook("allow", Set.of(HookEvent.TOOL_CALL_BEFORE),
                HookResult.allow()));

        HookResult result = registry.fireBefore(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        assertFalse(result.allowed());
        assertEquals("circuit open", result.blockReason());
    }

    @Test
    void fireBefore_ModificationsAccumulated() {
        registry.register(new StubBeforeHook("h1", Set.of(HookEvent.LLM_CALL_BEFORE),
                HookResult.allowWith(Map.of("context", "memory recall"))));
        registry.register(new StubBeforeHook("h2", Set.of(HookEvent.LLM_CALL_BEFORE),
                HookResult.allowWith(Map.of("extra", "value"))));

        HookResult result = registry.fireBefore(HookEvent.LLM_CALL_BEFORE, "s1", Map.of());
        assertTrue(result.allowed());
        assertEquals("memory recall", result.getModification("context", String.class));
        assertEquals("value", result.getModification("extra", String.class));
    }

    @Test
    void fireBefore_NoHooks_ReturnsAllow() {
        HookResult result = registry.fireBefore(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        assertTrue(result.allowed());
    }

    @Test
    void fireBefore_Ordering() {
        // order=10 should run before order=50
        var executionOrder = new java.util.ArrayList<String>();
        registry.register(new OrderedBeforeHook("late", 50, executionOrder));
        registry.register(new OrderedBeforeHook("early", 10, executionOrder));

        registry.fireBefore(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        assertEquals(List.of("early", "late"), executionOrder);
    }

    @Test
    void fireBefore_ExceptionSkipsHook() {
        registry.register(new StubBeforeHook("crash", Set.of(HookEvent.TOOL_CALL_BEFORE), null) {
            @Override
            public HookResult before(HookContext context) {
                throw new RuntimeException("boom");
            }
        });
        registry.register(new StubBeforeHook("ok", Set.of(HookEvent.TOOL_CALL_BEFORE), HookResult.allow()));

        HookResult result = registry.fireBefore(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of());
        assertTrue(result.allowed()); // crash skipped, ok runs
    }

    // ── fireAfter ───────────────────────────────────────────────────────────────

    @Test
    void fireAfter_AllExecute() {
        var counter = new int[]{0};
        registry.register(new StubAfterHook("h1", Set.of(HookEvent.TOOL_CALL_AFTER),
                ctx -> counter[0]++));
        registry.register(new StubAfterHook("h2", Set.of(HookEvent.TOOL_CALL_AFTER),
                ctx -> counter[0]++));

        registry.fireAfter(HookEvent.TOOL_CALL_AFTER, "s1", Map.of());
        assertEquals(2, counter[0]);
    }

    @Test
    void fireAfter_ExceptionDoesNotPropagate() {
        registry.register(new StubAfterHook("crash", Set.of(HookEvent.SESSION_START),
                ctx -> { throw new RuntimeException("boom"); }));
        var counter = new int[]{0};
        registry.register(new StubAfterHook("ok", Set.of(HookEvent.SESSION_START),
                ctx -> counter[0]++));

        // Should not throw
        registry.fireAfter(HookEvent.SESSION_START, "s1", Map.of());
        assertEquals(1, counter[0]); // ok hook still runs after crash
    }

    @Test
    void fireAfter_NoHooks_NoException() {
        registry.fireAfter(HookEvent.SESSION_END, "s1", Map.of());
    }

    // ── HookContext ─────────────────────────────────────────────────────────────

    @Test
    void hookContextTypedAccess() {
        Map<String, Object> data = Map.of(
                "name", "read_file",
                "duration", 150L,
                "count", 42,
                "active", true
        );
        HookContext ctx = new HookContext(HookEvent.TOOL_CALL_AFTER, "s1", data);

        assertEquals("read_file", ctx.getString("name"));
        assertEquals(150L, ctx.getLong("duration"));
        assertEquals(42L, ctx.getLong("count")); // int → long
        assertTrue(ctx.getBoolean("active"));
        assertNull(ctx.getString("missing"));
    }

    @Test
    void hookContextWith() {
        HookContext original = new HookContext(HookEvent.TOOL_CALL_BEFORE, "s1", Map.of("a", 1));
        HookContext derived = original.with("b", 2);

        assertEquals(1, derived.data().get("a"));
        assertEquals(2, derived.data().get("b"));
        assertEquals(1, original.data().size()); // original unchanged
    }

    // ── HookResult ──────────────────────────────────────────────────────────────

    @Test
    void hookResultAllowIsSingleton() {
        assertSame(HookResult.allow(), HookResult.allow());
    }

    @Test
    void hookResultBlock() {
        HookResult r = HookResult.block("reason");
        assertFalse(r.allowed());
        assertEquals("reason", r.blockReason());
        assertFalse(r.hasModifications());
    }

    @Test
    void hookResultAllowWith() {
        HookResult r = HookResult.allowWith(Map.of("key", "value"));
        assertTrue(r.allowed());
        assertTrue(r.hasModifications());
        assertEquals("value", r.getModification("key", String.class));
    }

    // ── Stub implementations ────────────────────────────────────────────────────

    static class StubAfterHook implements AfterHook {
        private final String hookName;
        private final Set<HookEvent> events;
        private final java.util.function.Consumer<HookContext> afterAction;

        StubAfterHook(String name, Set<HookEvent> events) {
            this(name, events, null);
        }

        StubAfterHook(String name, Set<HookEvent> events, java.util.function.Consumer<HookContext> afterAction) {
            this.hookName = name;
            this.events = events;
            this.afterAction = afterAction;
        }

        @Override public String name() { return hookName; }
        @Override public Set<HookEvent> supportedEvents() { return events; }
        @Override public void after(HookContext context) {
            if (afterAction != null) afterAction.accept(context);
        }
    }

    static class StubBeforeHook implements BeforeHook {
        private final String hookName;
        private final Set<HookEvent> events;
        private final HookResult result;

        StubBeforeHook(String name, Set<HookEvent> events, HookResult result) {
            this.hookName = name;
            this.events = events;
            this.result = result;
        }

        @Override public String name() { return hookName; }
        @Override public Set<HookEvent> supportedEvents() { return events; }
        @Override public HookResult before(HookContext context) { return result; }
    }

    static class OrderedBeforeHook implements BeforeHook {
        private final String hookName;
        private final int order;
        private final List<String> executionOrder;

        OrderedBeforeHook(String name, int order, List<String> executionOrder) {
            this.hookName = name;
            this.order = order;
            this.executionOrder = executionOrder;
        }

        @Override public String name() { return hookName; }
        @Override public int order() { return order; }
        @Override public Set<HookEvent> supportedEvents() { return Set.of(HookEvent.TOOL_CALL_BEFORE); }
        @Override public HookResult before(HookContext context) {
            executionOrder.add(hookName);
            return HookResult.allow();
        }
    }

    static class NoopChannelEventBus implements ChannelEventBus {
        @Override public void publish(ChannelEvent event) {}
        @Override public void subscribe(String channelName, Set<HookEvent> eventFilter, ChannelSubscriber subscriber) {}
        @Override public void unsubscribe(String channelName) {}
        @Override public void streamToken(String sessionId, String token, boolean reasoning) {}
        @Override public void subscribeTokens(String channelName, TokenSubscriber subscriber) {}
        @Override public void unsubscribeTokens(String channelName) {}
    }
}
