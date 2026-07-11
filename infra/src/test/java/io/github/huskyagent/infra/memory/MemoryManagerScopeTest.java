package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerScopeTest {

    @AfterEach
    void clearContext() {
        SessionContext.clear();
    }

    @Test
    void buildSystemPromptUsesExplicitScopeWithoutThreadLocal() {
        RecordingStrategy strategy = new RecordingStrategy("default");
        MemoryManager manager = manager(strategy);
        manager.registerProvider(new FakeProvider("builtin"));
        SessionScope scope = scope("default", Set.of("builtin"));

        String prompt = manager.buildSystemPrompt(scope);

        assertEquals("prompt:default", prompt);
        assertSame(scope, strategy.loadRequest.scope());
        assertEquals(List.of("builtin"), strategy.loadRequest.providers().stream().map(MemoryProvider::getName).toList());
        assertNull(SessionContext.getScope());
    }

    @Test
    void searchFromToolFiltersProvidersFromExplicitScope() {
        RecordingStrategy strategy = new RecordingStrategy("default");
        MemoryManager manager = manager(strategy);
        manager.registerProvider(new FakeProvider("session"));
        manager.registerProvider(new FakeProvider("builtin"));
        SessionScope scope = scope("default", Set.of("session"));

        MemoryResult result = manager.searchFromTool(scope, "session", "query", MemorySearchOptions.ofTopK(3), "all");

        assertEquals("strategy", result.providerName());
        assertSame(scope, strategy.searchRequest.scope());
        assertEquals("all", strategy.searchRequest.requestedScope());
        assertEquals(List.of("session"), strategy.searchRequest.providers().stream().map(MemoryProvider::getName).toList());
    }

    @Test
    void disabledProviderSelectionReturnsEmptyProviders() {
        RecordingStrategy strategy = new RecordingStrategy("default");
        MemoryManager manager = manager(strategy);
        manager.registerProvider(new FakeProvider("session"));
        SessionScope scope = scope("default", Set.of("builtin"));

        manager.searchFromTool(scope, "session", "query", MemorySearchOptions.ofTopK(3), "current");

        assertTrue(strategy.searchRequest.providers().isEmpty());
        assertFalse(manager.isProviderEnabled(scope, "session"));
    }

    @Test
    void syncAndWriteUseExplicitScope() {
        RecordingStrategy strategy = new RecordingStrategy("default");
        MemoryManager manager = manager(strategy);
        manager.registerProvider(new FakeProvider("builtin"));
        SessionScope scope = scope("default", Set.of("builtin"));

        manager.syncAll(scope, "user", "assistant");
        MemoryWriteResult writeResult = manager.writeFromTool(scope, "memory_append", Map.of("content", "x"));

        assertSame(scope, strategy.turnRequest.scope());
        assertEquals("user", strategy.turnRequest.user());
        assertSame(scope, strategy.writeRequest.scope());
        assertTrue(writeResult.success());
    }

    @Test
    void strategySelectionUsesExplicitScope() {
        RecordingStrategy defaultStrategy = new RecordingStrategy("default");
        RecordingStrategy recallStrategy = new RecordingStrategy("session-recall");
        MemoryManager manager = new MemoryManager(new MemoryRuntimeStrategyResolver(List.of(defaultStrategy, recallStrategy)));
        manager.registerProvider(new FakeProvider("session"));

        manager.buildSystemPrompt(scope("session-recall", Set.of("session")));

        assertNull(defaultStrategy.loadRequest);
        assertNotNull(recallStrategy.loadRequest);
    }

    @Test
    void publicOperationsRequireExplicitScope() {
        MemoryManager manager = manager(new RecordingStrategy("default"));

        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.buildSystemPrompt(null)).getMessage());
        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.prefetchAll(null, "query", MemorySearchOptions.ofTopK(3))).getMessage());
        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.searchFromTool(null, "session", "query", MemorySearchOptions.ofTopK(3), "all")).getMessage());
        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.syncAll(null, "user", "assistant")).getMessage());
        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.writeFromTool(null, "memory_append", Map.of())).getMessage());
        assertEquals("SessionScope is required for memory operations",
                assertThrows(NullPointerException.class, () -> manager.isProviderEnabled(null, "builtin")).getMessage());
    }

    private MemoryManager manager(MemoryRuntimeStrategy strategy) {
        return new MemoryManager(new MemoryRuntimeStrategyResolver(List.of(strategy)));
    }

    private SessionScope scope(String strategyId, Set<String> providerIds) {
        return SessionScope.builder()
                .sessionId("session-1")
                .principalId("principal-1")
                .channelType("http")
                .agentId("scene-1")
                .memoryPolicy("SESSION")
                .memoryStrategyId(strategyId)
                .memoryProviderIds(providerIds)
                .memoryWriteAllowed(true)
                .allowCrossSessionMemorySearch(true)
                .build();
    }

    private static class RecordingStrategy implements MemoryRuntimeStrategy {
        private final String id;
        MemoryLoadRequest loadRequest;
        MemorySearchRequest searchRequest;
        MemoryTurnRequest turnRequest;
        MemoryWriteRequest writeRequest;

        RecordingStrategy(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public MemoryLoadResult loadForPrompt(MemoryLoadRequest request) {
            this.loadRequest = request;
            return new MemoryLoadResult("prompt:" + id);
        }

        @Override
        public MemoryResult search(MemorySearchRequest request) {
            this.searchRequest = request;
            return MemoryResult.empty("strategy");
        }

        @Override
        public MemoryWriteResult write(MemoryWriteRequest request) {
            this.writeRequest = request;
            return MemoryWriteResult.success("written");
        }

        @Override
        public MemoryTurnResult afterTurn(MemoryTurnRequest request) {
            this.turnRequest = request;
            return new MemoryTurnResult(request.providers().size());
        }
    }

    private static class FakeProvider implements MemoryProvider {
        private final String name;

        FakeProvider(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void initialize(MemoryContext context) {
        }

        @Override
        public String buildSystemPrompt() {
            return "";
        }

        @Override
        public MemoryResult prefetch(String query, MemorySearchOptions options) {
            return MemoryResult.empty(name);
        }
    }
}
