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

/**
 * ReAct Agent 的图状态
 *
 * <p>在 MessagesState 基础上扩展了三个通道：</p>
 * <ul>
 *   <li>{@link #TOOL_EXECUTION_REQUESTS} - 待执行的工具调用队列（LinkedList），
 *       每次 action_dispatcher 节点取队列头部处理，工具执行完后弹出</li>
 *   <li>{@link #APPROVAL_RESULT} - 审批结果通道（"APPROVED"/"REJECTED"/""），
 *       approval 节点通过检测此字段是否为空来决定是否触发 interrupt</li>
 *   <li>{@link #SESSION_ALLOWED_TOOLS} - 会话级已授权工具集合（Set<String>），
 *       用户选择 always 后写入，后续同名工具直接放行</li>
 * </ul>
 */
public class ReActAgentState extends MessagesState<Message> {

    // ── 通道 key 常量 ──────────────────────────────────────────────────────────

    /**
     * 待执行工具调用队列（每轮 LLM 响应中可能包含多个 tool_call）
     */
    public static final String TOOL_EXECUTION_REQUESTS = "tool_execution_requests";

    /**
     * action_dispatcher 计算出的下一跳节点名（供 dispatchAction 边读取）
     * 使用 base channel 每次覆盖写入
     */
    public static final String NEXT_ACTION = "next_action";

    /**
     * 审批结果。
     * <ul>
     *   <li>""（空字符串）或不存在：尚未审批 → approval 节点会 interrupt</li>
     *   <li>"APPROVED"：用户已批准 → approvalAction 路由到实际工具节点</li>
     *   <li>"REJECTED"：用户已拒绝 → approvalAction 生成拒绝消息后路由回 model</li>
     * </ul>
     */
    public static final String APPROVAL_RESULT = "approval_result";

    /**
     * 会话级已授权工具白名单（ALLOW_SESSION 语义：once always，不再询问）
     */
    public static final String SESSION_ALLOWED_TOOLS = "session_allowed_tools";

    /**
     * 用户交互中断的回答结果。
     */
    public static final String CLARIFY_RESULT = "clarify_result";

    /**
     * model 节点调用次数（即 ReAct loop 轮次）。
     * 每次 callModelAction 执行后 +1，由 shouldContinueAction 检查是否超限。
     * 使用自定义 reducer 做累加。
     */
    public static final String MODEL_CALL_COUNT = "model_call_count";

    /**
     * 各工具连续失败次数，Map&lt;toolName, count&gt;，整体替换
     */
    public static final String TOOL_RETRY_COUNTS = "tool_retry_counts";

    /**
     * 工具错误历史，追加模式，每次失败追加一条描述，供 LLM 反思
     */
    public static final String TOOL_ERROR_HISTORY = "tool_error_history";

    /**
     * 最近一次工具执行是否失败，供 afterToolAction 路由判断
     */
    public static final String LAST_TOOL_FAILED = "last_tool_failed";

    /**
     * 最近一次 LLM 调用的 token 用量（覆盖写入，每次 model 节点执行后更新）
     */
    public static final String LAST_TOKEN_USAGE = "last_token_usage";

    // ── Schema ─────────────────────────────────────────────────────────────────

    /**
     * 完整 Schema，合并父类 messages 通道。
     *
     * <p>TOOL_EXECUTION_REQUESTS 用 {@code Channels.base(LinkedList::new)} 而不是 appender，
     * 因为我们需要整体替换（弹出队列头部），而不是追加。</p>
     *
     * <p>APPROVAL_RESULT 有自定义 reducer：接受 AgentEx.ApprovalState enum 或 String，
     * 统一转成 String 存储；接受 AgentState.MARK_FOR_REMOVAL 时清除（框架约定）。</p>
     *
     * <p>SESSION_ALLOWED_TOOLS 用 {@code Channels.base(HashSet::new)} 整体替换更新。</p>
     */
    public static final Map<String, Channel<?>> SCHEMA = mergeMap(
            MessagesState.SCHEMA,
            Map.of(
                    TOOL_EXECUTION_REQUESTS, Channels.base((java.util.function.Supplier<List<AssistantMessage.ToolCall>>) LinkedList::new),
                    NEXT_ACTION, Channels.base((java.util.function.Supplier<String>) () -> ""),
                    APPROVAL_RESULT, Channels.<String>base((prev, next) -> next instanceof String ? (String) next : String.valueOf(next)),
                    SESSION_ALLOWED_TOOLS, Channels.base((java.util.function.Supplier<Set<String>>) HashSet::new),
                    CLARIFY_RESULT, Channels.<String>base((prev, next) -> next instanceof String ? (String) next : String.valueOf(next)),
                    // 普通覆盖 channel：调用方负责读取当前值再 +1 后写回，
                    // 这样 buildInputs 显式写 0 就能在每轮对话开始时重置
                    MODEL_CALL_COUNT, Channels.<Integer>base(() -> 0),
                    TOOL_RETRY_COUNTS, Channels.<Map<String, Integer>>base((Supplier<Map<String, Integer>>) HashMap::new),
                    TOOL_ERROR_HISTORY, Channels.<String>appender((Supplier<List<String>>) ArrayList::new),
                    LAST_TOOL_FAILED, Channels.<Boolean>base(() -> false),
                    LAST_TOKEN_USAGE, Channels.<TokenUsage>base(TokenUsage::empty)
            )
    );

    // ── 构造 ───────────────────────────────────────────────────────────────────

    public ReActAgentState(Map<String, Object> initData) {
        super(initData);
    }

    // ── 访问器 ─────────────────────────────────────────────────────────────────

    /**
     * 获取待执行工具调用队列（整个 List）
     */
    @SuppressWarnings("unchecked")
    public List<AssistantMessage.ToolCall> toolExecutionRequests() {
        return this.<List<AssistantMessage.ToolCall>>value(TOOL_EXECUTION_REQUESTS)
                .orElse(List.of());
    }

    /**
     * 返回去掉队列第一个之后的剩余列表（用于工具执行完后更新状态）
     */
    public List<AssistantMessage.ToolCall> toolExecutionRequestsWithoutFirst() {
        List<AssistantMessage.ToolCall> current = toolExecutionRequests();
        if (current.isEmpty()) return List.of();
        return current.stream().skip(1).toList();
    }

    /**
     * 从最后一条 AssistantMessage 中加载 tool_calls（首次派发时调用）
     */
    public Optional<List<AssistantMessage.ToolCall>> loadToolCallsFromLastMessage() {
        return lastMessage()
                .filter(m -> MessageType.ASSISTANT == m.getMessageType())
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .map(AssistantMessage::getToolCalls);
    }

    /**
     * 当前审批结果（空字符串 or 不存在 表示尚未审批）
     */
    public Optional<String> approvalResult() {
        return this.<String>value(APPROVAL_RESULT)
                .filter(s -> !s.isEmpty());
    }

    /**
     * 当前用户交互中断回答（空字符串 or 不存在 表示尚未回答）
     */
    public Optional<String> clarifyResult() {
        return this.<String>value(CLARIFY_RESULT)
                .filter(s -> !s.isEmpty());
    }

    /**
     * 获取会话级工具白名单
     *
     * <p>Jackson cloneState 会把 HashSet 序列化成 JSON Array，再反序列化为 LinkedList，
     * 因此这里兼容任意 Collection 类型，统一转为 Set 返回。</p>
     */
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

    /**
     * 判断指定工具是否已在会话白名单中
     */
    public boolean isToolSessionAllowed(String toolName) {
        return sessionAllowedTools().contains(toolName);
    }

    /**
     * 当前 model 调用次数（= ReAct loop 轮次）
     */
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

