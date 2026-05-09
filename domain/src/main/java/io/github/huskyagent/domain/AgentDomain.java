package io.github.huskyagent.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import io.github.huskyagent.infra.ai.ChatClientProvider;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class AgentDomain {

    private static final String REACT_SYSTEM_PROMPT = """
        You are an intelligent assistant that can use tools to help users solve problems.
        Follow these steps:
        1. Thought: analyze the user's request
        2. Action: decide whether tools are needed
        3. Observation: observe tool execution results
        4. Final Answer: provide the final answer
        """;

    private final ChatClientProvider chatClientProvider;

    public ChatClient createReActAgent(List<?> tools) {
        ToolCallbackProvider toolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
        return chatClientProvider.createChatClient(toolProvider, REACT_SYSTEM_PROMPT);
    }

    public String chat(ChatClient client, String message) {
        log.info("Executing chat: {}", message);
        return client.prompt()
                .user(message)
                .call()
                .content();
    }
}
