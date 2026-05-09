package io.github.huskyagent.infra.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenCounter {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
            .mapToInt(this::countMessageTokens)
            .sum();
    }

    public int countMessageTokens(Message message) {
        String content = message.getText();
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(content) + 4;
    }

    public int countTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    public int countMessagesWithinBudget(List<Message> messages, int tokenBudget) {
        int accumulated = 0;
        int count = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            int msgTokens = countMessageTokens(messages.get(i));
            if (accumulated + msgTokens > tokenBudget) {
                break;
            }
            accumulated += msgTokens;
            count++;
        }

        return count;
    }

    public int findBoundaryByTokens(List<Message> messages, int startIndex, int tokenBudget) {
        int accumulated = 0;
        int boundary = messages.size();

        for (int i = messages.size() - 1; i >= startIndex; i--) {
            int msgTokens = countMessageTokens(messages.get(i));
            if (accumulated + msgTokens > tokenBudget) {
                boundary = i + 1;
                break;
            }
            accumulated += msgTokens;
        }

        return boundary;
    }
}