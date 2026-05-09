package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.context.ContextConfig;
import io.github.huskyagent.infra.context.ContextEngine;
import io.github.huskyagent.infra.context.ContextStatus;
import io.github.huskyagent.infra.context.TokenCounter;
import io.github.huskyagent.infra.context.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextManagerTest {

    @Test
    void prepareActiveContextUsesProvidedMessagesWithoutLoadingSessionHistory() {
        ContextEngine engine = mock(ContextEngine.class);
        SessionManager sessionManager = mock(SessionManager.class);
        HookRegistry hookRegistry = mock(HookRegistry.class);
        ContextManagementStrategy strategy = mock(ContextManagementStrategy.class);
        when(strategy.id()).thenReturn("default");
        ContextManagementStrategyResolver resolver = new ContextManagementStrategyResolver(List.of(strategy));
        ContextManager manager = new ContextManager(
                engine,
                sessionManager,
                new TokenCounter(),
                new ContextConfig(),
                hookRegistry,
                resolver);
        List<Message> activeMessages = List.of(new UserMessage("active"));
        List<Message> compacted = List.of(new UserMessage("compacted"));
        when(strategy.prepare(any())).thenReturn(ContextManagementResult.changed(compacted, "default", "test", null));
        RuntimePolicy policy = RuntimePolicy.builder()
                .contextPolicy(ContextPolicy.builder()
                        .enabled(true)
                        .strategyId("default")
                        .contextLength(10)
                        .thresholdPercent(0.1)
                        .build())
                .build();

        List<Message> result = manager.prepareActiveContext("session", policy, "scene", activeMessages);

        assertSame(compacted, result);
        verify(sessionManager, never()).loadMessages(anyString());
        verify(strategy).prepare(argThat(request -> request.persistedMessages() == activeMessages));
    }

    @Test
    void prepareActiveContextReturnsUnchangedWhenRuntimePolicyDisabled() {
        ContextManager manager = new ContextManager(
                mock(ContextEngine.class),
                mock(SessionManager.class),
                new TokenCounter(),
                new ContextConfig(),
                mock(HookRegistry.class),
                new ContextManagementStrategyResolver(List.of(new NoopStrategy())));
        List<Message> activeMessages = List.of(new UserMessage("active"));
        RuntimePolicy policy = RuntimePolicy.builder()
                .contextPolicy(ContextPolicy.builder().enabled(false).build())
                .build();

        List<Message> result = manager.prepareActiveContext("session", policy, "scene", activeMessages);

        assertSame(activeMessages, result);
    }

    @Test
    void providerPromptTokensTriggerRuntimeCompressionWhenEstimateIsBelowThreshold() {
        ContextEngine engine = new DefaultContextEngine(
                new ContextConfig(),
                new TokenCounter(),
                new NoopPruneStrategy(),
                new NoopSummaryStrategy());
        SessionManager sessionManager = mock(SessionManager.class);
        HookRegistry hookRegistry = mock(HookRegistry.class);
        ContextManagementStrategy strategy = mock(ContextManagementStrategy.class);
        when(strategy.id()).thenReturn("default");
        ContextManagementStrategyResolver resolver = new ContextManagementStrategyResolver(List.of(strategy));
        ContextManager manager = new ContextManager(engine, sessionManager, new TokenCounter(), new ContextConfig(), hookRegistry, resolver);
        List<Message> activeMessages = List.of(new UserMessage("short"));
        List<Message> compacted = List.of(new UserMessage("compacted"));
        when(strategy.prepare(any())).thenReturn(ContextManagementResult.changed(compacted, "default", "test", null));
        RuntimePolicy policy = runtimePolicy(1000, 0.75);

        manager.updateTokenUsage("session", new TokenUsage(900, 0, 900));
        List<Message> result = manager.prepareActiveContext("session", policy, "scene", activeMessages);

        assertSame(compacted, result);
        verify(strategy).prepare(argThat(request -> request.currentTokens() == 900));
    }

    @Test
    void localEstimateStillTriggersRuntimeCompressionWithoutProviderUsage() {
        ContextManagementStrategy strategy = mock(ContextManagementStrategy.class);
        when(strategy.id()).thenReturn("default");
        List<Message> activeMessages = List.of(new UserMessage("hello world"));
        when(strategy.prepare(any())).thenAnswer(invocation ->
                ContextManagementResult.unchanged(activeMessages, "default", "test"));
        ContextManager manager = new ContextManager(
                mock(ContextEngine.class),
                mock(SessionManager.class),
                new TokenCounter(),
                new ContextConfig(),
                mock(HookRegistry.class),
                new ContextManagementStrategyResolver(List.of(strategy)));
        RuntimePolicy policy = runtimePolicy(1, 0.1);

        manager.prepareActiveContext("session", policy, "scene", activeMessages);

        verify(strategy).prepare(argThat(request -> request.currentTokens() > 0));
    }

    @Test
    void zeroProviderPromptTokensDoNotOverrideLocalEstimate() {
        ContextEngine engine = new DefaultContextEngine(
                new ContextConfig(),
                new TokenCounter(),
                new NoopPruneStrategy(),
                new NoopSummaryStrategy());
        ContextManagementStrategy strategy = mock(ContextManagementStrategy.class);
        when(strategy.id()).thenReturn("default");
        List<Message> activeMessages = List.of(new UserMessage("short"));
        when(strategy.prepare(any())).thenAnswer(invocation ->
                ContextManagementResult.unchanged(activeMessages, "default", "test"));
        ContextManager manager = new ContextManager(
                engine,
                mock(SessionManager.class),
                new TokenCounter(),
                new ContextConfig(),
                mock(HookRegistry.class),
                new ContextManagementStrategyResolver(List.of(strategy)));
        RuntimePolicy policy = runtimePolicy(1000, 0.75);

        manager.updateTokenUsage("session", new TokenUsage(0, 0, 0));
        manager.prepareActiveContext("session", policy, "scene", activeMessages);

        verify(strategy).prepare(argThat(request -> request.currentTokens() < 750));
    }

    private RuntimePolicy runtimePolicy(int contextLength, double thresholdPercent) {
        return RuntimePolicy.builder()
                .contextPolicy(ContextPolicy.builder()
                        .enabled(true)
                        .strategyId("default")
                        .contextLength(contextLength)
                        .thresholdPercent(thresholdPercent)
                        .protectFirstN(1)
                        .tailTokenBudget(20)
                        .maxSummaryTokens(100)
                        .build())
                .build();
    }

    private static class NoopPruneStrategy implements PruneStrategy {
        @Override
        public List<Message> prune(List<Message> input, PruneConfig config) {
            return input;
        }

        @Override
        public String getName() {
            return "noop";
        }
    }

    private static class NoopSummaryStrategy implements SummaryStrategy {
        @Override
        public String generate(List<Message> turns, SummaryConfig config) {
            return "summary";
        }

        @Override
        public String update(String previousSummary, List<Message> newTurns, SummaryConfig config) {
            return previousSummary;
        }

        @Override
        public String getName() {
            return "noop";
        }
    }

    private static class NoopStrategy implements ContextManagementStrategy {
        @Override
        public String id() {
            return "default";
        }

        @Override
        public ContextManagementResult prepare(ContextManagementRequest request) {
            return ContextManagementResult.unchanged(request.persistedMessages(), id(), "noop");
        }
    }
}
