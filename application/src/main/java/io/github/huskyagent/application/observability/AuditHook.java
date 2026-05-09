package io.github.huskyagent.application.observability;

import io.github.huskyagent.domain.hook.AfterHook;
import io.github.huskyagent.domain.hook.AgentHook;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.observability.SecretRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.StringJoiner;

/**
 * Audit Hook — 结构化审计日志。
 *
 * <p>输出格式为 key=value，便于 logback 过滤和日志分析工具解析。
 * 所有字符串值经 SecretRedactor 处理防止密钥泄漏。</p>
 *
 * <p>默认启用，可通过 {@code husky.observability.audit.enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "husky.observability.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditHook implements AfterHook {

    /** 独立 logger 名，便于 logback 按名过滤审计日志 */
    private static final org.slf4j.Logger AUDIT_LOG =
            org.slf4j.LoggerFactory.getLogger("io.github.huskyagent.audit");

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(
                HookEvent.SESSION_START,
                HookEvent.SESSION_END,
                HookEvent.TOOL_CALL_AFTER,
                HookEvent.LLM_CALL_AFTER,
                HookEvent.APPROVAL_AFTER,
                HookEvent.CONTEXT_COMPRESS
        );
    }

    @Override
    public void after(HookContext context) {
        StringJoiner sj = new StringJoiner(" ");
        sj.add("event=" + context.event());
        sj.add("session=" + redact(context.sessionId()));

        switch (context.event()) {
            case TOOL_CALL_AFTER -> appendToolCall(context, sj);
            case LLM_CALL_AFTER -> appendLlmCall(context, sj);
            case SESSION_END -> appendSessionEnd(context, sj);
            case APPROVAL_AFTER -> appendApproval(context, sj);
            case CONTEXT_COMPRESS -> appendCompression(context, sj);
            default -> {}
        }

        AUDIT_LOG.info(sj.toString());
    }

    private void appendToolCall(HookContext ctx, StringJoiner sj) {
        sj.add("tool=" + redact(ctx.getString(HookDataKeys.TOOL_NAME)));
        sj.add("status=" + ctx.getString(HookDataKeys.TOOL_STATUS));
        Long duration = ctx.getLong(HookDataKeys.TOOL_DURATION_MS);
        if (duration != null) sj.add("duration=" + duration + "ms");
        String error = ctx.getString(HookDataKeys.TOOL_ERROR);
        if (error != null) sj.add("error=" + redact(error));
    }

    private void appendLlmCall(HookContext ctx, StringJoiner sj) {
        Long duration = ctx.getLong(HookDataKeys.LLM_DURATION_MS);
        if (duration != null) sj.add("duration=" + duration + "ms");
        Boolean hasToolCalls = ctx.getBoolean(HookDataKeys.LLM_HAS_TOOL_CALLS);
        if (hasToolCalls != null) sj.add("hasToolCalls=" + hasToolCalls);
        Long callCount = ctx.getLong(HookDataKeys.LLM_MODEL_CALL_COUNT);
        if (callCount != null) sj.add("modelCallCount=" + callCount);
    }

    private void appendSessionEnd(HookContext ctx, StringJoiner sj) {
        Long duration = ctx.getLong(HookDataKeys.SESSION_DURATION_MS);
        if (duration != null) sj.add("duration=" + duration + "ms");
        Long inputTokens = ctx.getLong(HookDataKeys.SESSION_INPUT_TOKENS);
        if (inputTokens != null) sj.add("inputTokens=" + inputTokens);
        Long outputTokens = ctx.getLong(HookDataKeys.SESSION_OUTPUT_TOKENS);
        if (outputTokens != null) sj.add("outputTokens=" + outputTokens);
    }

    private void appendApproval(HookContext ctx, StringJoiner sj) {
        String decision = ctx.getString(HookDataKeys.APPROVAL_DECISION);
        if (decision != null) sj.add("decision=" + decision);
        String toolName = ctx.getString(HookDataKeys.TOOL_NAME);
        if (toolName != null) sj.add("tool=" + redact(toolName));
    }

    private void appendCompression(HookContext ctx, StringJoiner sj) {
        Long originalTokens = ctx.getLong(HookDataKeys.COMPRESS_ORIGINAL_TOKENS);
        if (originalTokens != null) sj.add("originalTokens=" + originalTokens);
        Object originalCount = ctx.data().get(HookDataKeys.COMPRESS_ORIGINAL_COUNT);
        if (originalCount != null) sj.add("originalMessages=" + originalCount);
        Object resultCount = ctx.data().get(HookDataKeys.COMPRESS_RESULT_COUNT);
        if (resultCount != null) sj.add("resultMessages=" + resultCount);
    }

    private String redact(String value) {
        return SecretRedactor.redact(value);
    }
}