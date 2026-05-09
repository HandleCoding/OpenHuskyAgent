package io.github.huskyagent.domain.hook;

public final class HookDataKeys {

    private HookDataKeys() {}

    public static final String TOOL_NAME = "toolName";
    public static final String TOOL_ARGS = "toolArgs";
    public static final String TOOL_ARGS_PREVIEW = "argsPreview";
    public static final String TOOL_RESULT = "toolResult";
    public static final String TOOL_DURATION_MS = "durationMs";
    public static final String TOOL_ERROR = "error";
    public static final String TOOL_CALL_ID = "toolCallId";
    /** "started" / "completed" / "failed" */
    public static final String TOOL_STATUS = "toolStatus";

    public static final String LLM_MESSAGES = "messages";
    public static final String LLM_RESPONSE = "response";
    public static final String LLM_FINISH_REASON = "finishReason";
    public static final String LLM_DURATION_MS = "durationMs";
    public static final String LLM_MODEL_CALL_COUNT = "modelCallCount";
    public static final String LLM_HAS_TOOL_CALLS = "hasToolCalls";
    public static final String LLM_CONTEXT_INJECT = "context";
    public static final String LLM_TOKEN_USAGE = "tokenUsage";

    public static final String APPROVAL_REASON = "reason";
    /** "approved" / "rejected" */
    public static final String APPROVAL_DECISION = "decision";
    public static final String APPROVAL_ALWAYS = "always";

    public static final String CLARIFY_TYPE = "clarifyType";
    public static final String CLARIFY_QUESTION = "question";
    public static final String CLARIFY_ANSWER = "answer";

    public static final String SESSION_MESSAGE = "message";
    public static final String SESSION_INPUT_TOKENS = "inputTokens";
    public static final String SESSION_OUTPUT_TOKENS = "outputTokens";
    public static final String SESSION_DURATION_MS = "sessionDurationMs";

    public static final String COMPRESS_ORIGINAL_COUNT = "originalMessageCount";
    public static final String COMPRESS_RESULT_COUNT = "compressedMessageCount";
    public static final String COMPRESS_ORIGINAL_TOKENS = "originalTokens";

    public static final String SUBAGENT_ID = "subagentId";
    public static final String SUBAGENT_GOAL = "subagentGoal";
    public static final String SUBAGENT_DEPTH = "subagentDepth";
    public static final String SUBAGENT_STATUS = "subagentStatus";
    public static final String SUBAGENT_SUMMARY = "subagentSummary";
    public static final String SUBAGENT_DURATION_MS = "subagentDurationMs";
    public static final String SUBAGENT_ERROR = "subagentError";
    public static final String SUBAGENT_TOOL_TRACE = "subagentToolTrace";
}
