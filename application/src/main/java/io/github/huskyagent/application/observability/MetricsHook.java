package io.github.huskyagent.application.observability;

import io.github.huskyagent.domain.hook.AfterHook;
import io.github.huskyagent.domain.hook.AgentHook;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Records runtime counters and timers when Micrometer is present.
 */
@Slf4j
@Component
@ConditionalOnClass(MeterRegistry.class)
public class MetricsHook implements AfterHook {

    private final MeterRegistry meterRegistry;

    public MetricsHook(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("MetricsHook initialized with MeterRegistry");
    }

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(
                HookEvent.TOOL_CALL_AFTER,
                HookEvent.LLM_CALL_AFTER,
                HookEvent.SESSION_END,
                HookEvent.CONTEXT_COMPRESS,
                HookEvent.APPROVAL_AFTER,
                HookEvent.SUBAGENT_STOP
        );
    }

    @Override
    public void after(HookContext context) {
        switch (context.event()) {
            case TOOL_CALL_AFTER -> recordToolCall(context);
            case LLM_CALL_AFTER -> recordLlmCall(context);
            case SESSION_END -> recordSession(context);
            case CONTEXT_COMPRESS -> recordCompression(context);
            case APPROVAL_AFTER -> recordApproval(context);
            case SUBAGENT_STOP -> recordSubAgent(context);
            default -> {}
        }
    }

    private void recordToolCall(HookContext context) {
        String toolName = context.getString(HookDataKeys.TOOL_NAME);
        String status = context.getString(HookDataKeys.TOOL_STATUS);
        Long durationMs = context.getLong(HookDataKeys.TOOL_DURATION_MS);

        if (toolName == null) toolName = "unknown";
        if (status == null) status = "unknown";

        meterRegistry.counter("husky.tool.calls", "toolName", toolName, "status", status).increment();

        if (durationMs != null && durationMs > 0) {
            meterRegistry.timer("husky.tool.duration", "toolName", toolName)
                    .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    private void recordLlmCall(HookContext context) {
        Boolean hasToolCalls = context.getBoolean(HookDataKeys.LLM_HAS_TOOL_CALLS);
        Long durationMs = context.getLong(HookDataKeys.LLM_DURATION_MS);

        String toolCallsTag = hasToolCalls != null ? String.valueOf(hasToolCalls) : "unknown";

        meterRegistry.counter("husky.llm.calls", "hasToolCalls", toolCallsTag).increment();

        if (durationMs != null && durationMs > 0) {
            meterRegistry.timer("husky.llm.duration")
                    .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    private void recordSession(HookContext context) {
        meterRegistry.counter("husky.session.count").increment();

        Long durationMs = context.getLong(HookDataKeys.SESSION_DURATION_MS);
        if (durationMs != null && durationMs > 0) {
            meterRegistry.timer("husky.session.duration")
                    .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    private void recordCompression(HookContext context) {
        meterRegistry.counter("husky.context.compressions").increment();
    }

    private void recordApproval(HookContext context) {
        String decision = context.getString(HookDataKeys.APPROVAL_DECISION);
        if (decision == null) decision = "unknown";
        meterRegistry.counter("husky.approval.requests", "decision", decision).increment();
    }

    private void recordSubAgent(HookContext context) {
        String status = context.getString(HookDataKeys.SUBAGENT_STATUS);
        if (status == null) status = "unknown";
        meterRegistry.counter("husky.subagent.calls", "status", status).increment();
    }
}