package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

public record ContextCompressionWindow(List<Message> head, List<Message> middle, List<Message> suffix) {
    public static ContextCompressionWindow of(List<Message> messages, int protectFirstN) {
        int headEnd = Math.min(Math.max(protectFirstN, 0), messages.size());
        int suffixStart = Math.max(headEnd, lastUserMessageIndex(messages));

        return new ContextCompressionWindow(
                messages.subList(0, headEnd),
                messages.subList(headEnd, suffixStart),
                messages.subList(suffixStart, messages.size()));
    }

    private static int lastUserMessageIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getMessageType() == MessageType.USER) {
                return i;
            }
        }
        return messages.size();
    }
}
