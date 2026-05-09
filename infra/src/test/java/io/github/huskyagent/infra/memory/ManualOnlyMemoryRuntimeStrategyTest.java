package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.config.HuskyDataPaths;
import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManualOnlyMemoryRuntimeStrategyTest {

    private ManualOnlyMemoryRuntimeStrategy strategy;
    private DefaultMemoryRuntimeStrategy defaultStrategy;

    @BeforeEach
    void setUp() {
        defaultStrategy = new DefaultMemoryRuntimeStrategy(new MemoryScopeResolver());
        strategy = new ManualOnlyMemoryRuntimeStrategy(defaultStrategy);
    }

    @Test
    void idReturnsManualOnly() {
        assertEquals("manual-only", strategy.id());
    }

    @Test
    void loadForPromptAlwaysReturnsEmpty() {
        FakeProvider provider = new FakeProvider("p", "some-content");

        MemoryLoadResult result = strategy.loadForPrompt(new MemoryLoadRequest(
                scope("SESSION", true, true),
                List.of(provider)));

        assertTrue(result.prompt() == null || result.prompt().isBlank(),
                "manual-only should never inject prompt automatically");
    }

    @Test
    void loadForPromptEmptyWithNoProviders() {
        MemoryLoadResult result = strategy.loadForPrompt(new MemoryLoadRequest(null, List.of()));

        assertTrue(result.prompt() == null || result.prompt().isBlank());
    }

    @Test
    void searchPrefetchReturnsEmpty() {
        FakeProvider provider = new FakeProvider("p", "content");

        MemoryResult result = strategy.search(new MemorySearchRequest(
                scope("SESSION", true, true),
                List.of(provider),
                "query",
                MemorySearchOptions.ofTopK(5),
                "current",
                MemorySearchTrigger.PREFETCH));

        assertEquals("manual-only", result.providerName());
        assertTrue(result.isEmpty(), "prefetch should return empty in manual-only mode");
    }

    @Test
    void searchToolTriggerExecutesNormally() {
        FakeProvider provider = new FakeProvider("p", "content");

        MemoryResult result = strategy.search(new MemorySearchRequest(
                scope("SESSION", true, true),
                List.of(provider),
                "query",
                MemorySearchOptions.ofTopK(5),
                "current",
                MemorySearchTrigger.TOOL));

        assertFalse(result.isEmpty(), "tool-triggered search should execute normally and return results");
    }

    @Test
    void afterTurnDoesNotSyncAnyProvider() {
        FakeProvider provider = new FakeProvider("p", "content");

        MemoryTurnResult result = strategy.afterTurn(new MemoryTurnRequest(
                scope("SESSION", true, true),
                List.of(provider),
                "user message",
                "assistant response"));

        assertEquals(0, result.syncedProviders(), "manual-only should not sync any providers automatically");
        assertFalse(provider.synced, "provider.syncTurn should not be called");
    }

    @Test
    void writeRejectsDisabledMemory() {
        FakeBuiltinProvider builtinProvider = new FakeBuiltinProvider();

        MemoryWriteResult result = strategy.write(new MemoryWriteRequest(
                scope("DISABLED", false, false),
                List.of(builtinProvider),
                "memory_write",
                Map.of("content", "test")));

        assertFalse(result.success(), "write should fail when memory is DISABLED");
    }

    @Test
    void writeRejectsReadonlyScope() {
        FakeBuiltinProvider builtinProvider = new FakeBuiltinProvider();

        MemoryWriteResult result = strategy.write(new MemoryWriteRequest(
                scope("READONLY", false, false),
                List.of(builtinProvider),
                "memory_write",
                Map.of("content", "test")));

        assertFalse(result.success(), "write should fail when write is not allowed");
    }

    @Test
    void writeRoutesToBuiltinProviderWhenAllowed() {
        FakeBuiltinProvider builtinProvider = new FakeBuiltinProvider();

        MemoryWriteResult result = strategy.write(new MemoryWriteRequest(
                scope("SESSION", true, false),
                List.of(builtinProvider),
                "memory_write",
                Map.of("content", "hello")));

        assertTrue(result.success(), "write should succeed when policy allows");
        assertTrue(builtinProvider.called, "BuiltinMemoryProvider.handleToolCall should be invoked");
    }

    // --- helpers ---

    private SessionScope scope(String memoryPolicy, boolean writeAllowed, boolean allowCrossSessionSearch) {
        return SessionScope.builder()
                .sessionId("session")
                .principalId("principal")
                .channelType("http")
                .sceneId("scene")
                .memoryPolicy(memoryPolicy)
                .memoryStrategyId("manual-only")
                .memoryWriteAllowed(writeAllowed)
                .allowCrossSessionMemorySearch(allowCrossSessionSearch)
                .build();
    }

    private static class FakeProvider implements MemoryProvider {
        final String name;
        final String prompt;
        boolean synced;

        FakeProvider(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
        }

        @Override public String getName() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public void initialize(MemoryContext ctx) {}
        @Override public String buildSystemPrompt() { return prompt; }
        @Override public MemoryResult prefetch(String q, MemorySearchOptions o) {
            return MemoryResult.of(List.of(MemoryEntry.of("id", "content", 1.0, name)), name);
        }
        @Override public void syncTurn(String user, String assistant) { synced = true; }
    }

    private static class FakeBuiltinProvider extends BuiltinMemoryProvider {
        boolean called;

        FakeBuiltinProvider() {
            super(null, null, new HuskyDataPaths(""));
        }

        @Override
        public String getName() {
            return BuiltinMemoryProvider.NAME;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String handleToolCall(String toolName, Map<String, Object> args) {
            called = true;
            return "ok";
        }
    }
}
