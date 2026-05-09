package io.github.huskyagent.domain.graph;

import io.github.huskyagent.infra.context.TokenUsage;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.*;
import java.util.function.Supplier;

import static org.bsc.langgraph4j.utils.CollectionsUtils.mergeMap;

public class ReActAgentState extends MessagesState<Message> {

    /** Queue of tool calls waiting to be executed by downstream graph nodes. */
    public static final String TOOL_EXECUTION_REQUESTS = "tool_execution_requests";

    /** Explicit next-hop override used by dispatcher-style graph transitions. */
    public static final String NEXT_ACTION = "next_action";

    /** Approval decision written back after an approval interrupt resumes. */
    public static final String APPROVAL_RESULT = "approval_result";

    /** Session-scoped allowlist accumulated from prior user approvals. */
    public static final String SESSION_ALLOWED_TOOLS = "session_allowed_tools";

    /** User answer captured from a clarification interrupt. */
    public static final String CLARIFY_RESULT = "clarify_result";

    /** Number of model invocations performed in the current turn. */
    public static final String MODEL_CALL_COUNT = "model_call_count";

    /** Per-tool retry counters used by tool retry policies. */
    public static final String TOOL_RETRY_COUNTS = "tool_retry_counts";

    /** Appended history of tool error summaries for the current turn. */
    public static final String TOOL_ERROR_HISTORY = "tool_error_history";

    /** Whether the most recent tool attempt failed. */
    public static final String LAST_TOOL_FAILED = "last_tool_failed";

    /** Provider-reported token usage for the latest model call. */
    public static final String LAST_TOKEN_USAGE = "last_token_usage";

    // ── Schema ─────────────────────────────────────────────────────────────────

    /**
     * State schema shared by all graph nodes.
     *
     * <p>Each entry defines the merge behavior for one logical state channel.</p>
     */
    public static final Map<String, Channel<?>> SCHEMA = mergeMap(
            MessagesState.SCHEMA,
            Map.of(
                    TOOL_EXECUTION_REQUESTS, Channels.base((java.util.function.Supplier<List<AssistantMessage.ToolCall>>) LinkedList::new),
                    NEXT_ACTION, Channels.base((java.util.function.Supplier<String>) () -> ""),
                    APPROVAL_RESULT, Channels.<String>base((prev, next) -> next instanceof String ? (String) next : String.valueOf(next)),
                    SESSION_ALLOWED_TOOLS, Channels.base((java.util.function.Supplier<Set<String>>) HashSet::new),
                    CLARIFY_RESULT, Channels.<String>base((prev, next) -> next instanceof String ? (String) next : String.valueOf(next)),
                    MODEL_CALL_COUNT, Channels.<Integer>base(() -> 0),
                    TOOL_RETRY_COUNTS, Channels.<Map<String, Integer>>base((Supplier<Map<String, Integer>>) HashMap::new),
                    TOOL_ERROR_HISTORY, Channels.<String>appender((Supplier<List<String>>) ArrayList::new),
                    LAST_TOOL_FAILED, Channels.<Boolean>base(() -> false),
                    LAST_TOKEN_USAGE, Channels.<TokenUsage>base(TokenUsage::empty)
            )
    );


    public ReActAgentState(Map<String, Object> initData) {
        super(initData);
    }


    @SuppressWarnings("unchecked")
    public List<AssistantMessage.ToolCall> toolExecutionRequests() {
        return this.<List<AssistantMessage.ToolCall>>value(TOOL_EXECUTION_REQUESTS)
                .orElse(List.of());
    }

    public List<AssistantMessage.ToolCall> toolExecutionRequestsWithoutFirst() {
        List<AssistantMessage.ToolCall> current = toolExecutionRequests();
        if (current.isEmpty()) return List.of();
        return current.stream().skip(1).toList();
    }

    public Optional<List<AssistantMessage.ToolCall>> loadToolCallsFromLastMessage() {
        return lastMessage()
                .filter(m -> MessageType.ASSISTANT == m.getMessageType())
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .map(AssistantMessage::getToolCalls);
    }

    public Optional<String> approvalResult() {
        return this.<String>value(APPROVAL_RESULT)
                .filter(s -> !s.isEmpty());
    }

    public Optional<String> clarifyResult() {
        return this.<String>value(CLARIFY_RESULT)
                .filter(s -> !s.isEmpty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Set<String> sessionAllowedTools() {
        return this.value(SESSION_ALLOWED_TOOLS)
                .map(v -> {
                    if (v instanceof Set<?> s) {
                        return (Set<String>) s;
                    }
                    if (v instanceof java.util.Collection<?> c) {
                        return new HashSet<>((java.util.Collection<String>) c);
                    }
                    return Set.<String>of();
                })
                .orElse(Set.of());
    }

    public boolean isToolSessionAllowed(String toolName) {
        return sessionAllowedTools().contains(toolName);
    }

    public int modelCallCount() {
        return this.<Integer>value(MODEL_CALL_COUNT).orElse(0);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Integer> toolRetryCounts() {
        return this.value(TOOL_RETRY_COUNTS)
                .map(v -> (Map<String, Integer>) v)
                .orElse(Map.of());
    }

    @SuppressWarnings("unchecked")
    public List<String> toolErrorHistory() {
        return this.value(TOOL_ERROR_HISTORY)
                .map(v -> (List<String>) v)
                .orElse(List.of());
    }

    public boolean lastToolFailed() {
        return this.<Boolean>value(LAST_TOOL_FAILED).orElse(false);
    }

    @SuppressWarnings("unchecked")
    public Optional<TokenUsage> lastTokenUsage() {
        return this.value(LAST_TOKEN_USAGE).flatMap(raw -> {
            TokenUsage u;
            if (raw instanceof TokenUsage tu) {
                u = tu;
            } else if (raw instanceof java.util.Map) {
                try {
                    var m = (java.util.Map<String, Object>) raw;
                    u = new TokenUsage(
                            ((Number) m.getOrDefault("promptTokens", 0)).intValue(),
                            ((Number) m.getOrDefault("completionTokens", 0)).intValue(),
                            ((Number) m.getOrDefault("totalTokens", 0)).intValue());
                } catch (Exception e) {
                    u = TokenUsage.empty();
                }
            } else {
                u = TokenUsage.empty();
            }
            return u.promptTokens() > 0 || u.completionTokens() > 0 || u.totalTokens() > 0 ? Optional.of(u) : Optional.empty();
        });
    }
}

