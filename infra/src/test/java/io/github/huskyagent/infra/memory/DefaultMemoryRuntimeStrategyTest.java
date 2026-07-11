package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMemoryRuntimeStrategyTest {

    @Test
    void loadForPromptAggregatesAvailableProviders() {
        DefaultMemoryRuntimeStrategy strategy = new DefaultMemoryRuntimeStrategy(new MemoryScopeResolver());

        MemoryLoadResult result = strategy.loadForPrompt(new MemoryLoadRequest(
                scope("SESSION", true, true),
                List.of(new FakeProvider("left", "A"), new FakeProvider("right", "B"))));

        assertEquals("A\nB", result.prompt());
    }

    @Test
    void searchRejectsCrossSessionWhenPolicyDisallowsIt() {
        DefaultMemoryRuntimeStrategy strategy = new DefaultMemoryRuntimeStrategy(new MemoryScopeResolver());

        MemoryResult result = strategy.search(new MemorySearchRequest(
                scope("SESSION", true, false),
                List.of(new FakeProvider("provider", "prompt")),
                "query",
                MemorySearchOptions.ofTopK(5),
                "all",
                MemorySearchTrigger.TOOL));

        assertEquals("cross-session-disabled", result.providerName());
        assertTrue(result.isEmpty());
    }

    @Test
    void afterTurnSkipsReadonlyPolicy() {
        DefaultMemoryRuntimeStrategy strategy = new DefaultMemoryRuntimeStrategy(new MemoryScopeResolver());
        FakeProvider provider = new FakeProvider("provider", "prompt");

        MemoryTurnResult result = strategy.afterTurn(new MemoryTurnRequest(
                scope("READONLY", false, true),
                List.of(provider),
                "user",
                "assistant"));

        assertEquals(0, result.syncedProviders());
        assertFalse(provider.synced);
    }

    private SessionScope scope(String memoryPolicy, boolean writeAllowed, boolean allowCrossSessionSearch) {
        return SessionScope.builder()
                .sessionId("session")
                .principalId("principal")
                .channelType("http")
                .agentId("scene")
                .memoryPolicy(memoryPolicy)
                .memoryStrategyId("default")
                .memoryWriteAllowed(writeAllowed)
                .allowCrossSessionMemorySearch(allowCrossSessionSearch)
                .build();
    }

    private static class FakeProvider implements MemoryProvider {
        private final String name;
        private final String prompt;
        boolean synced;

        FakeProvider(String name, String prompt) {
            this.name = name;
            this.prompt = prompt;
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
            return prompt;
        }

        @Override
        public MemoryResult prefetch(String query, MemorySearchOptions options) {
            return MemoryResult.of(List.of(MemoryEntry.of("id", "content", 1.0, name)), name);
        }

        @Override
        public void syncTurn(String user, String assistant) {
            synced = true;
        }
    }
}
