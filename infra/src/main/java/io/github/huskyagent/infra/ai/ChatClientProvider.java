package io.github.huskyagent.infra.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatClientProvider {

    private final ChatModel chatModel;

    private ChatClient chatClient;

    public ChatClient createChatClient(ToolCallbackProvider toolCallbackProvider, String systemPrompt) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
        return this.chatClient;
    }

    public ChatClient createChatClient(String systemPrompt) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
        return this.chatClient;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }
}
