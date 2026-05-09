package io.github.huskyagent.infra.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 计数器
 * 使用 jtokkit（Java tiktoken 实现）进行精确计数，正确处理 CJK 字符
 */
@Component
public class TokenCounter {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding;

    public TokenCounter() {
        // 使用 cl100k_base 编码（GPT-4/GLM 等模型通用），对中文友好
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * 精确计算消息列表的 token 数
     */
    public int countTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
            .mapToInt(this::countMessageTokens)
            .sum();
    }

    /**
     * 精确计算单条消息的 token 数
     */
    public int countMessageTokens(Message message) {
        String content = message.getText();
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(content) + 4; // 角色开销约 4 tokens
    }

    /**
     * 精确计算文本的 token 数
     */
    public int countTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 计算 token 预算下能容纳多少消息（从后往前）
     */
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

    /**
     * 找到 token 预算下的边界位置（从后往前累积）
     */
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