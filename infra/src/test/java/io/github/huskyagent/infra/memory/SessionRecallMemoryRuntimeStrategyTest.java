package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.config.HuskyDataPaths;
import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionRecallMemoryRuntimeStrategyTest {

    private SessionRecallMemoryRuntimeStrategy strategy;

    @BeforeEach
    void setUp() {
        MemoryScopeResolver scopeResolver = new MemoryScopeResolver();
        DefaultMemoryRuntimeStrategy defaultStrategy = new DefaultMemoryRuntimeStrategy(scopeResolver);
        strategy = new SessionRecallMemoryRuntimeStrategy(scopeResolver, defaultStrategy);
    }

    @Test
    void idReturnsSessionRecall() {
        assertEquals("session-recall", strategy.id());
    }

    @Test
    void loadForPromptReturnsEmpty() {
        MemoryLoadResult result = strategy.loadForPrompt(new MemoryLoadRequest(
                scope("SESSION", true, true),
                List.of(new TrackingProvider("p"))));

        assertTrue(result.prompt() == null || result.prompt().isBlank(),
                "session-recall should never inject prompt from any provider");
    }

    @Test
    void loadForPromptIgnoresBuiltinProvider() {
        TrackingProvider provider = new TrackingProvider("builtin");

        strategy.loadForPrompt(new MemoryLoadRequest(null, List.of(provider)));

        assertFalse(provider.buildSystemPromptCalled, "buildSystemPrompt should not be called");
    }

    @Test
    void searchOnlyQueriesScopedProviders() {
        TrackingProvider nonScoped = new TrackingProvider("non-scoped");
        FakeScopedProvider scoped = new FakeScopedProvider("scoped");

        strategy.search(new MemorySearchRequest(
                scope("SESSION", true, false),
                List.of(nonScoped, scoped),
                "query",
                MemorySearchOptions.ofTopK(5),
                "all",
                MemorySearchTrigger.PREFETCH));

        assertFalse(nonScoped.prefetchCalled, "non-ScopedMemoryProvider should not be queried");
        assertTrue(scoped.prefetchCalled, "ScopedMemoryProvider should be queried");
    }

    @Test
    void searchForcesCurrentSessionScope() {
        FakeScopedProvider scoped = new FakeScopedProvider("scoped");

        // request scope="all" but strategy should force "current"
        strategy.search(new MemorySearchRequest(
                scope("SESSION", true, true),
                List.of(scoped),
                "query",
                MemorySearchOptions.ofTopK(5),
                "all",
                MemorySearchTrigger.PREFETCH));

        assertNotNull(scoped.lastScope, "scope should have been passed");
        assertEquals(MemoryScope.SearchBoundary.CURRENT_SESSION, scoped.lastScope.getBoundary(),
                "session-recall should always force current-session boundary regardless of requested scope");
    }

    @Test
    void afterTurnOnlySyncsToScopedProviders() {
        TrackingProvider nonScoped = new TrackingProvider("non-scoped");
        FakeScopedProvider scoped = new FakeScopedProvider("scoped");

        MemoryTurnResult result = strategy.afterTurn(new MemoryTurnRequest(
                scope("SESSION", true, false),
                List.of(nonScoped, scoped),
                "user",
                "assistant"));

        assertFalse(nonScoped.syncTurnCalled, "non-ScopedMemoryProvider should not be synced");
        assertTrue(scoped.syncTurnCalled, "ScopedMemoryProvider should be synced");
        assertEquals(1, result.syncedProviders());
    }

    @Test
    void afterTurnSkipsDisabledPolicy() {
        FakeScopedProvider scoped = new FakeScopedProvider("scoped");

        MemoryTurnResult result = strategy.afterTurn(new MemoryTurnRequest(
                scope("DISABLED", false, false),
                List.of(scoped),
                "user",
                "assistant"));

        assertEquals(0, result.syncedProviders());
        assertFalse(scoped.syncTurnCalled);
    }

    @Test
    void afterTurnSkipsReadonlyPolicy() {
        FakeScopedProvider scoped = new FakeScopedProvider("scoped");

        MemoryTurnResult result = strategy.afterTurn(new MemoryTurnRequest(
                scope("READONLY", false, false),
                List.of(scoped),
                "user",
                "assistant"));

        assertEquals(0, result.syncedProviders());
        assertFalse(scoped.syncTurnCalled);
    }

    @Test
    void writeRoutesNormally() {
        FakeBuiltinProvider builtinProvider = new FakeBuiltinProvider();

        MemoryWriteResult result = strategy.write(new MemoryWriteRequest(
                scope("SESSION", true, false),
                List.of(builtinProvider),
                "memory_write",
                Map.of("content", "hello")));

        assertTrue(result.success());
        assertTrue(builtinProvider.called);
    }

    // --- helpers ---

    private SessionScope scope(String memoryPolicy, boolean writeAllowed, boolean allowCrossSessionSearch) {
        return SessionScope.builder()
                .sessionId("session")
                .principalId("principal")
                .channelType("http")
                .sceneId("scene")
                .memoryPolicy(memoryPolicy)
                .memoryStrategyId("session-recall")
                .memoryWriteAllowed(writeAllowed)
                .allowCrossSessionMemorySearch(allowCrossSessionSearch)
                .build();
    }

    // non-ScopedMemoryProvider that tracks calls
    private static class TrackingProvider implements MemoryProvider {
        final String name;
        boolean buildSystemPromptCalled;
        boolean prefetchCalled;
        boolean syncTurnCalled;

        TrackingProvider(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public void initialize(MemoryContext ctx) {}
        @Override public String buildSystemPrompt() { buildSystemPromptCalled = true; return ""; }
        @Override public MemoryResult prefetch(String q, MemorySearchOptions o) {
            prefetchCalled = true;
            return MemoryResult.empty(name);
        }
        @Override public void syncTurn(String user, String assistant) { syncTurnCalled = true; }
    }

    // ScopedMemoryProvider that tracks which scope was passed
    private static class FakeScopedProvider implements ScopedMemoryProvider {
        final String name;
        boolean prefetchCalled;
        boolean syncTurnCalled;
        MemoryScope lastScope;

        FakeScopedProvider(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public void initialize(MemoryContext ctx) {}
        @Override public String buildSystemPrompt() { return ""; }
        @Override public MemoryResult prefetch(String q, MemorySearchOptions o) {
            return MemoryResult.empty(name);
        }
        @Override public MemoryResult prefetch(String query, MemorySearchOptions options, MemoryScope scope) {
            prefetchCalled = true;
            lastScope = scope;
            return MemoryResult.of(List.of(MemoryEntry.of("id", "content", 1.0, name)), name);
        }
        @Override public void syncTurn(String user, String assistant) {}
        @Override public void syncTurn(String user, String assistant, MemoryScope scope) {
            syncTurnCalled = true;
            lastScope = scope;
        }
    }

    private static class FakeBuiltinProvider extends BuiltinMemoryProvider {
        boolean called;

        FakeBuiltinProvider() {
            super(null, null, new HuskyDataPaths(""));
        }

        @Override public String getName() { return BuiltinMemoryProvider.NAME; }
        @Override public boolean isAvailable() { return true; }
        @Override public String handleToolCall(String toolName, Map<String, Object> args) {
            called = true;
            return "ok";
        }
    }
}
