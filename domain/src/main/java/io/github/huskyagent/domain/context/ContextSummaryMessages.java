package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

public final class ContextSummaryMessages {
    public static final String SUMMARY_PREFIX = "[对话历史摘要]";

    private ContextSummaryMessages() {
    }

    public static boolean isSummary(Message message) {
        return message instanceof SystemMessage
                && message.getText() != null
                && message.getText().startsWith(SUMMARY_PREFIX);
    }

    public static List<Message> withoutSummaries(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> !isSummary(message))
                .toList();
    }

    public static List<Message> summaryInput(List<Message> head, List<Message> middle, List<Message> tail) {
        List<Message> result = new ArrayList<>();
        addSummaries(result, head);
        if (middle != null) {
            result.addAll(middle);
        }
        addSummaries(result, tail);
        return result;
    }

    public static SystemMessage summaryMessage(String summary) {
        return new SystemMessage(SUMMARY_PREFIX + "\n" + summary);
    }

    private static void addSummaries(List<Message> result, List<Message> messages) {
        if (messages == null) {
            return;
        }
        messages.stream()
                .filter(ContextSummaryMessages::isSummary)
                .forEach(result::add);
    }
}
