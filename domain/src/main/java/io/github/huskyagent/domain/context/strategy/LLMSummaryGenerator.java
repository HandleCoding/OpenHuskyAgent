package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.domain.context.SummaryConfig;
import io.github.huskyagent.domain.context.SummaryStrategy;
import io.github.huskyagent.infra.context.ContextConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 摘要生成策略
 * 使用结构化模板生成对话摘要
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMSummaryGenerator implements SummaryStrategy {

    private final ContextConfig config;
    private ChatModel summaryChatModel;

    private static final String SUMMARY_PROMPT_TEMPLATE = """
        请将以下对话历史压缩为一个结构化摘要，保留关键信息以便后续继续工作。

        ## 对话历史
        %s

        ## 摘要格式
        请按以下格式输出摘要：

        ## Active Task
        [用户最新请求原文]

        ## Goal
        [用户整体目标]

        ## Constraints & Preferences
        [用户偏好、约束、重要决策]

        ## Completed Actions
        [已完成的操作，带工具名]

        ## Active State
        [当前状态：目录、文件、测试状态]

        ## In Progress
        [正在进行的工作]

        ## Blocked
        [未解决的阻塞问题]

        ## Key Decisions
        [关键决策]

        ## Resolved Questions
        [已解决的问题]

        ## Pending User Asks
        [待用户确认的问题]

        ## Relevant Files
        [相关文件列表]

        ## Remaining Work
        [剩余工作]
        """;

    private static final String UPDATE_PROMPT_TEMPLATE = """
        已有摘要：
        %s

        新增对话：
        %s

        请更新摘要，合并新信息，保持格式一致。
        """;

    @Override
    public String getName() {
        return "llmSummary";
    }

    /**
     * 设置摘要模型（延迟初始化）
     */
    public void setSummaryChatModel(ChatModel chatModel) {
        this.summaryChatModel = chatModel;
    }

    @Override
    public String generate(List<Message> turns, SummaryConfig summaryConfig) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        if (summaryChatModel == null) {
            log.warn("Summary chat model not initialized, returning simple concatenation");
            return simpleSummarize(turns);
        }

        try {
            String conversationText = formatMessages(turns);
            String prompt = String.format(SUMMARY_PROMPT_TEMPLATE, conversationText);

            ChatClient chatClient = ChatClient.create(summaryChatModel);
            String summary = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            log.info("Generated summary: {} chars", summary != null ? summary.length() : 0);
            return summary != null ? summary : "";

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

        if (summaryChatModel == null) {
            log.warn("Summary chat model not initialized, returning previous summary");
            return previousSummary;
        }

        try {
            String newConversationText = formatMessages(newTurns);
            String prompt = String.format(UPDATE_PROMPT_TEMPLATE, previousSummary, newConversationText);

            ChatClient chatClient = ChatClient.create(summaryChatModel);
            String updated = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            log.info("Updated summary: {} chars", updated != null ? updated.length() : 0);
            return updated != null ? updated : previousSummary;

        } catch (Exception e) {
            log.error("Failed to update summary", e);
            return previousSummary;
        }
    }

    /**
     * 格式化消息列表为文本
     */
    private String formatMessages(List<Message> messages) {
        return messages.stream()
            .map(this::formatMessage)
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * 格式化单条消息
     */
    private String formatMessage(Message msg) {
        String role = msg.getMessageType().getValue();
        String content = msg.getText();
        return String.format("[%s]: %s", role, content);
    }

    /**
     * 简单摘要（fallback）
     */
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