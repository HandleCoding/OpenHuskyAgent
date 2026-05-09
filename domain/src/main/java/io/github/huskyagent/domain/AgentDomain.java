package io.github.huskyagent.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import io.github.huskyagent.infra.ai.ChatClientProvider;

import java.util.List;

/**
 * Agent 领域服务
 * 组合基础设施能力，提供 Agent 核心业务逻辑
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentDomain {

    private static final String REACT_SYSTEM_PROMPT = """
        你是一个智能助手，可以使用工具来帮助用户解决问题。
        请按照以下步骤工作：
        1. Thought: 分析用户的问题
        2. Action: 决定是否需要使用工具
        3. Observation: 观察工具执行结果
        4. Final Answer: 给出最终答案
        """;

    private final ChatClientProvider chatClientProvider;

    /**
     * 创建 ReAct Agent
     */
    public ChatClient createReActAgent(List<?> tools) {
        ToolCallbackProvider toolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
        return chatClientProvider.createChatClient(toolProvider, REACT_SYSTEM_PROMPT);
    }

    /**
     * 执行 Agent 对话
     */
    public String chat(ChatClient client, String message) {
        log.info("执行对话: {}", message);
        return client.prompt()
                .user(message)
                .call()
                .content();
    }
}
