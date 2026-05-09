package io.github.huskyagent.domain.subagent;

import java.util.List;

/**
 * 子 Agent 事件类型 — 子 Agent 运行期间向父 Agent 推送的消息。
 *
 * <p>密封接口，所有变体按生命周期阶段组织：
 * Started → (ToolCallStarted | ToolCallCompleted | Progress)* → Completed | Failed | Timeout</p>
 */
public sealed interface SubAgentMessage
        permits SubAgentMessage.Started, SubAgentMessage.ToolCallStarted,
        SubAgentMessage.ToolCallCompleted, SubAgentMessage.Progress,
        SubAgentMessage.Completed, SubAgentMessage.Failed, SubAgentMessage.Timeout {

    /** 子 Agent 启动 */
    record Started(String sessionId, String goal, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent 开始调用工具 */
    record ToolCallStarted(String toolName, String argsPreview, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent 工具调用完成 */
    record ToolCallCompleted(String toolName, long durationMs, boolean success, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent LLM 输出中间文本 */
    record Progress(String text, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent 正常完成 */
    record Completed(String sessionId, String summary,
                     List<ToolTraceEntry> toolTrace, long durationMs,
                     int inputTokens, int outputTokens, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent 执行失败 */
    record Failed(String sessionId, String error, List<ToolTraceEntry> toolTrace, int taskIndex) implements SubAgentMessage {}

    /** 子 Agent 执行超时 */
    record Timeout(String sessionId, int taskIndex) implements SubAgentMessage {}

    /** 工具调用追踪条目 */
    record ToolTraceEntry(String tool, String status, long durationMs) {}
}
