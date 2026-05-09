package io.github.huskyagent.domain.state;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentState {

    private String sessionId;

    private List<Message> messages = new ArrayList<>();

    /** Current iteration count for bounded multi-step agent loops. */
    private int iteration = 0;

    /** Upper bound that stops the loop before it can recurse indefinitely. */
    private int maxIterations = 10;

    private AgentStatus status = AgentStatus.IDLE;

    private String lastResponse;

    private List<ToolCallInfo> pendingToolCalls = new ArrayList<>();

    private Map<String, String> toolResults = new HashMap<>();

    private boolean needsToolCall = false;

    private boolean shouldEnd = false;

    private String errorMessage;

    private Map<String, Object> data = new HashMap<>();

    public boolean isMaxIterationsReached() {
        return iteration >= maxIterations;
    }

    public void incrementIteration() {
        this.iteration++;
    }

    public void addMessage(Message message) {
        this.messages.add(message);
    }

    public void addToolResult(String toolCallId, String result) {
        this.toolResults.put(toolCallId, result);
    }

    public enum AgentStatus {
        IDLE,
        THINKING,
        ACTING,
        OBSERVING,
        COMPLETED,
        ERROR
    }

    @Data
    public static class ToolCallInfo {
        private String id;
        private String name;
        private String arguments;
    }
}