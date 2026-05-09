package io.github.huskyagent.infra.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ContextEngine {

    String getName();

    void updateFromResponse(TokenUsage usage);

    boolean shouldCompress(int promptTokens);

    List<Message> compress(List<Message> messages, int currentTokens);

    void onSessionStart(String sessionId);

    void onSessionEnd(String sessionId);

    void updateModel(String model, int contextLength);

    ContextStatus getStatus();
}