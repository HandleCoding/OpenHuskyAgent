package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.domain.context.SummaryConfig;
import io.github.huskyagent.domain.context.SummaryStrategy;
import io.github.huskyagent.infra.ai.AuxiliaryClient;
import io.github.huskyagent.infra.context.ContextConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Context compression summarizer backed by {@link AuxiliaryClient} (OpenAI-compatible transport).
 * On failure, falls back to a truncated concatenation so compression can still proceed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMSummaryGenerator implements SummaryStrategy {

    private final ContextConfig config;
    private final AuxiliaryClient auxiliaryClient;

    private static final String SUMMARY_PROMPT_TEMPLATE = """
        Compress the following conversation history into a structured summary while preserving key information needed to continue later.

        ## Conversation History
        %s

        ## Summary Format
        Use the following format:

        ## Active Task
        [Original latest user request]

        ## Goal
        [User's overall goal]

        ## Constraints & Preferences
        [User preferences, constraints, and important decisions]

        ## Completed Actions
        [Completed actions, including tool names]

        ## Active State
        [Current state: directories, files, test status]

        ## In Progress
        [Current work in progress]

        ## Blocked
        [Unresolved blockers]

        ## Key Decisions
        [Key decisions]

        ## Resolved Questions
        [Resolved issues]

        ## Pending User Asks
        [Questions awaiting user confirmation]

        ## Relevant Files
        [Relevant file list]

        ## Remaining Work
        [Remaining work]
        """;

    private static final String UPDATE_PROMPT_TEMPLATE = """
        Existing summary:
        %s

        New conversation:
        %s

        Update the summary, merge the new information, and keep the same format.
        """;

    @Override
    public String getName() {
        return "llmSummary";
    }

    @Override
    public String generate(List<Message> turns, SummaryConfig summaryConfig) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        try {
            String conversationText = formatMessages(turns);
            String prompt = String.format(SUMMARY_PROMPT_TEMPLATE, conversationText);
            Integer maxTokens = summaryConfig != null && summaryConfig.maxTokens() > 0
                    ? summaryConfig.maxTokens()
                    : config.getMaxSummaryTokens();
            String summary = auxiliaryClient.completeText(prompt, maxTokens);
            log.info("Generated summary: {} chars", summary != null ? summary.length() : 0);
            if (summary == null || summary.isBlank()) {
                log.warn("LLM summary empty, falling back to simple concatenation");
                return simpleSummarize(turns);
            }
            return summary;
        } catch (Exception e) {
            log.error("Failed to generate LLM summary", e);
            return simpleSummarize(turns);
        }
    }

    @Override
    public String update(String previousSummary, List<Message> newTurns, SummaryConfig summaryConfig) {
        if (previousSummary == null || previousSummary.isEmpty()) {
            return generate(newTurns, summaryConfig);
        }

        if (newTurns == null || newTurns.isEmpty()) {
            return previousSummary;
        }

        try {
            String newConversationText = formatMessages(newTurns);
            String prompt = String.format(UPDATE_PROMPT_TEMPLATE, previousSummary, newConversationText);
            Integer maxTokens = summaryConfig != null && summaryConfig.maxTokens() > 0
                    ? summaryConfig.maxTokens()
                    : config.getMaxSummaryTokens();
            String updated = auxiliaryClient.completeText(prompt, maxTokens);
            log.info("Updated summary: {} chars", updated != null ? updated.length() : 0);
            return updated != null && !updated.isBlank() ? updated : previousSummary;
        } catch (Exception e) {
            log.error("Failed to update summary", e);
            return previousSummary;
        }
    }

    private String formatMessages(List<Message> messages) {
        return messages.stream()
            .map(this::formatMessage)
            .collect(Collectors.joining("\n\n"));
    }

    private String formatMessage(Message msg) {
        String role = msg.getMessageType().getValue();
        String content = msg.getText();
        return String.format("[%s]: %s", role, content);
    }

    private String simpleSummarize(List<Message> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Conversation Summary\n\n");

        for (Message msg : turns) {
            String role = msg.getMessageType().getValue();
            String content = msg.getText();
            if (content != null && content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            sb.append("- **").append(role).append("**: ").append(content).append("\n");
        }

        return sb.toString();
    }
}
