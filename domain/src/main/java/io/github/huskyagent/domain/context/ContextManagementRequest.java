package io.github.huskyagent.domain.context;

import io.github.huskyagent.domain.context.policy.ContextPolicy;
import org.springframework.ai.chat.messages.Message;

import java.nio.file.Path;
import java.util.List;

public record ContextManagementRequest(
        String sessionId,
        String agentId,
        ContextPolicy policy,
        List<Message> persistedMessages,
        int currentTokens,
        Path workingDirectory,
        String model
) {
}