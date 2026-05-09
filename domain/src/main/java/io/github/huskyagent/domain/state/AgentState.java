package io.github.huskyagent.domain.state;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 状态
 * 在 langgraph4j 的 StateGraph 中流转的状态对象
 */
@Data
public class AgentState {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 消息历史
     */
    private List<Message> messages = new ArrayList<>();

    /**
     * 当前迭代次数
     */
    private int iteration = 0;

    /**
     * 最大迭代次数
     */
    private int maxIterations = 10;

    /**
     * 当前状态
     */
    private AgentStatus status = AgentStatus.IDLE;

    /**
     * 最后一次 LLM 响应
     */
    private String lastResponse;

    /**
     * 待处理的工具调用
     */
    private List<ToolCallInfo> pendingToolCalls = new ArrayList<>();

    /**
     * 工具调用结果
     */
    private Map<String, String> toolResults = new HashMap<>();

    /**
     * 是否需要调用工具
     */
    private boolean needsToolCall = false;

    /**
     * 是否应该结束
     */
    private boolean shouldEnd = false;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 额外数据
     */
    private Map<String, Object> data = new HashMap<>();

    /**
     * 检查是否超过最大迭代次数
     */
    public boolean isMaxIterationsReached() {
        return iteration >= maxIterations;
    }

    /**
     * 增加迭代次数
     */
    public void incrementIteration() {
        this.iteration++;
    }

    /**
     * 添加消息
     */
    public void addMessage(Message message) {
        this.messages.add(message);
    }

    /**
     * 添加工具调用结果
     */
    public void addToolResult(String toolCallId, String result) {
        this.toolResults.put(toolCallId, result);
    }

    /**
     * Agent 状态枚举
     */
    public enum AgentStatus {
        IDLE,           // 空闲
        THINKING,       // 思考中（调用 LLM）
        ACTING,         // 执行中（调用工具）
        OBSERVING,      // 观察中（处理工具结果）
        COMPLETED,      // 完成
        ERROR           // 错误
    }

    /**
     * 工具调用信息
     */
    @Data
    public static class ToolCallInfo {
        private String id;
        private String name;
        private String arguments;
    }
}