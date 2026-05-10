package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.context.strategy.DefaultContextManagementStrategy;
import io.github.huskyagent.domain.context.strategy.NoopContextManagementStrategy;
import io.github.huskyagent.domain.context.strategy.ToolResultPruner;
import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.infra.context.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagementStrategyTest {

    @Test
    void noneStrategyReturnsMessagesUnchanged() {
        List<Message> messages = List.of(new UserMessage("hello"));
        NoopContextManagementStrategy strategy = new NoopContextManagementStrategy();

        ContextManagementResult result = strategy.prepare(request(messages, policy("none"), 10));

        assertFalse(result.changed());
        assertSame(messages, result.messages());
        assertEquals("none", result.strategyId());
    }

    @Test
    void defaultStrategySkipsBelowPolicyThreshold() {
        List<Message> messages = List.of(new UserMessage("hello"));
        DefaultContextManagementStrategy strategy = new DefaultContextManagementStrategy(
                new TokenCounter(),
                new PruneStrategy() {
                    @Override
                    public List<Message> prune(List<Message> input, PruneConfig config) {
                        return input;
                    }

                    @Override
                    public String getName() {
                        return "test";
                    }
                },
                new SummaryStrategy() {
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
                        return "test";
                    }
                });

        ContextManagementResult result = strategy.prepare(request(messages, policy("default"), 10));

        assertFalse(result.changed());
        assertSame(messages, result.messages());
        assertEquals("below-threshold", result.reason());
    }

    @Test
    void defaultStrategyReplacesExistingSummaryInsteadOfStacking() {
        List<Message> messages = List.of(
                new UserMessage("protected"),
                new SystemMessage("[Conversation History Summary]\nold summary"),
                new UserMessage("middle message with enough words to exceed threshold"),
                new UserMessage("recent"));
        DefaultContextManagementStrategy strategy = new DefaultContextManagementStrategy(
                new TokenCounter(),
                new PruneStrategy() {
                    @Override
                    public List<Message> prune(List<Message> input, PruneConfig config) {
                        return input;
                    }

                    @Override
                    public String getName() {
                        return "test";
                    }
                },
                new SummaryStrategy() {
                    @Override
                    public String generate(List<Message> turns, SummaryConfig config) {
                        assertTrue(turns.stream().anyMatch(ContextSummaryMessages::isSummary));
                        return "new summary";
                    }

                    @Override
                    public String update(String previousSummary, List<Message> newTurns, SummaryConfig config) {
                        return previousSummary;
                    }

                    @Override
                    public String getName() {
                        return "test";
                    }
                });

        ContextManagementResult result = strategy.prepare(request(messages, smallPolicy(), 100));

        assertTrue(result.changed());
        assertEquals(1, result.messages().stream().filter(ContextSummaryMessages::isSummary).count());
        assertEquals("[Conversation History Summary]\nnew summary", result.messages().get(1).getText());
        assertEquals("protected", result.messages().get(0).getText());
        assertEquals("recent", result.messages().get(result.messages().size() - 1).getText());
    }

    @Test
    void defaultStrategyPreservesLastUserMessageAndFollowingMessages() {
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "read", "tool result after latest user")))
                .build();
        List<Message> messages = List.of(
                new UserMessage("protected head"),
                new UserMessage("old request should be summarized"),
                new AssistantMessage("old answer should be summarized"),
                new UserMessage("latest request must stay verbatim"),
                new AssistantMessage("assistant after latest request"),
                toolResponse);
        DefaultContextManagementStrategy strategy = new DefaultContextManagementStrategy(
                new TokenCounter(),
                new PruneStrategy() {
                    @Override
                    public List<Message> prune(List<Message> input, PruneConfig config) {
                        return input;
                    }

                    @Override
                    public String getName() {
                        return "test";
                    }
                },
                new SummaryStrategy() {
                    @Override
                    public String generate(List<Message> turns, SummaryConfig config) {
                        assertTrue(turns.stream().anyMatch(message -> "old request should be summarized".equals(message.getText())));
                        assertFalse(turns.stream().anyMatch(message -> "latest request must stay verbatim".equals(message.getText())));
                        return "middle summary";
                    }

                    @Override
                    public String update(String previousSummary, List<Message> newTurns, SummaryConfig config) {
                        return previousSummary;
                    }

                    @Override
                    public String getName() {
                        return "test";
                    }
                });

        ContextManagementResult result = strategy.prepare(request(messages, smallPolicy(), 100));

        assertTrue(result.changed());
        assertEquals("protected head", result.messages().get(0).getText());
        assertEquals("[Conversation History Summary]\nmiddle summary", result.messages().get(1).getText());
        assertEquals("latest request must stay verbatim", result.messages().get(2).getText());
        assertEquals("assistant after latest request", result.messages().get(3).getText());
        assertFalse(result.messages().contains(toolResponse));
    }

    @Test
    void toolResultPrunerTruncatesLongToolOutputWithNotice() {
        ToolLimitsConfig limitsConfig = new ToolLimitsConfig();
        limitsConfig.setPruneMaxToolResultLength(120);
        ToolResultPruner pruner = new ToolResultPruner(new TokenCounter(), limitsConfig);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "read", "1234567890".repeat(20))))
                .build();

        List<Message> result = pruner.prune(List.of(new UserMessage("protected"), toolResponse), PruneConfig.of(1, 100));

        ToolResponseMessage pruned = (ToolResponseMessage) result.get(1);
        String responseData = pruned.getResponses().get(0).responseData();
        assertEquals(120, responseData.length());
        assertTrue(responseData.startsWith("1234567890"));
        assertTrue(responseData.contains("tool output truncated from 200 chars because it exceeded the configured limit"));
    }

    private ContextPolicy smallPolicy() {
        return ContextPolicy.builder()
                .enabled(true)
                .mode("prune-then-summary")
                .strategyId("default")
                .pruneStrategyId("default")
                .summaryStrategyId("default")
                .thresholdPercent(0.1)
                .contextLength(10)
                .protectFirstN(1)
                .tailTokenBudget(20)
                .maxSummaryTokens(100)
                .build();
    }

    private ContextManagementRequest request(List<Message> messages, ContextPolicy policy, int tokens) {
        return new ContextManagementRequest("session", "scene", policy, messages, tokens, null, null);
    }

    private ContextPolicy policy(String strategyId) {
        return ContextPolicy.builder()
                .enabled(true)
                .mode("prune-then-summary")
                .strategyId(strategyId)
                .pruneStrategyId("default")
                .summaryStrategyId("default")
                .thresholdPercent(0.75)
                .contextLength(1000)
                .protectFirstN(1)
                .tailTokenBudget(100)
                .maxSummaryTokens(100)
                .build();
    }
}
