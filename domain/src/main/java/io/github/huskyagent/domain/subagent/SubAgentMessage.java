package io.github.huskyagent.domain.subagent;

import java.util.List;

public sealed interface SubAgentMessage
        permits SubAgentMessage.Started, SubAgentMessage.ToolCallStarted,
        SubAgentMessage.ToolCallCompleted, SubAgentMessage.Progress,
        SubAgentMessage.Completed, SubAgentMessage.Failed, SubAgentMessage.Timeout {

    record Started(String sessionId, String goal, int taskIndex) implements SubAgentMessage {}

    record ToolCallStarted(String toolName, String argsPreview, int taskIndex) implements SubAgentMessage {}

    record ToolCallCompleted(String toolName, long durationMs, boolean success, int taskIndex) implements SubAgentMessage {}

    record Progress(String text, int taskIndex) implements SubAgentMessage {}

    record Completed(String sessionId, String summary,
                     List<ToolTraceEntry> toolTrace, long durationMs,
                     int inputTokens, int outputTokens, int taskIndex) implements SubAgentMessage {}

    record Failed(String sessionId, String error, List<ToolTraceEntry> toolTrace, int taskIndex) implements SubAgentMessage {}

    record Timeout(String sessionId, int taskIndex) implements SubAgentMessage {}

    record ToolTraceEntry(String tool, String status, long durationMs) {}
}
