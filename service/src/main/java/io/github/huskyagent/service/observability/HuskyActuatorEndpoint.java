package io.github.huskyagent.service.observability;

import io.github.huskyagent.infra.session.SessionRepository;
import io.github.huskyagent.infra.session.SessionRepository.SessionStats;
import io.github.huskyagent.infra.session.SessionRepository.ToolUsageStat;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Husky Actuator Endpoint — 暴露 Agent 运行统计。
 *
 * <p>通过 {@code /actuator/husky} 可访问，返回会话统计、工具使用排行、LLM 调用指标等。</p>
 *
 * <p>注意：Micrometer 指标带 tag（如 toolName, status），不带 tag 查询会返回 0。
 * 这里使用 find() 聚合所有 tag 变体。</p>
 */
@Component
@Endpoint(id = "husky")
public class HuskyActuatorEndpoint {

    private final SessionRepository sessionRepository;
    private final MeterRegistry meterRegistry;

    public HuskyActuatorEndpoint(SessionRepository sessionRepository, MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.meterRegistry = meterRegistry;
    }

    @ReadOperation
    public Map<String, Object> huskyStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Session statistics from SQLite
        SessionStats sessionStats = sessionRepository.getSessionStats();
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("totalSessions", sessionStats.totalSessions());
        sessions.put("avgInputTokens", sessionStats.avgInputTokens());
        sessions.put("avgOutputTokens", sessionStats.avgOutputTokens());
        sessions.put("avgDurationMs", sessionStats.avgDurationMs());
        result.put("sessions", sessions);

        // Tool usage from Micrometer (聚合所有 tag 变体)
        Map<String, Object> toolMetrics = new LinkedHashMap<>();
        double totalToolCalls = sumCounters("husky.tool.calls");
        double totalToolDurationMs = sumTimerDuration("husky.tool.duration");
        toolMetrics.put("totalCalls", (long) totalToolCalls);
        toolMetrics.put("avgDurationMs", totalToolCalls > 0 ? totalToolDurationMs / totalToolCalls : 0);

        // Per-tool breakdown from Micrometer
        Map<String, Long> toolUsage = new LinkedHashMap<>();
        for (Counter c : meterRegistry.find("husky.tool.calls").counters()) {
            String toolName = c.getId().getTag("toolName");
            if (toolName != null) {
                toolUsage.merge(toolName, (long) c.count(), Long::sum);
            }
        }
        toolMetrics.put("perTool", toolUsage);
        result.put("toolMetrics", toolMetrics);

        // LLM call metrics from Micrometer
        Map<String, Object> llmMetrics = new LinkedHashMap<>();
        double llmCalls = sumCounters("husky.llm.calls");
        double llmDurationMs = sumTimerDuration("husky.llm.duration");
        llmMetrics.put("totalCalls", (long) llmCalls);
        llmMetrics.put("avgDurationMs", llmCalls > 0 ? llmDurationMs / llmCalls : 0);
        result.put("llmMetrics", llmMetrics);

        // Session duration from Micrometer
        double sessionCount = sumCounters("husky.session.count");
        double sessionDurationMs = sumTimerDuration("husky.session.duration");
        result.put("sessionMetrics", Map.of(
                "totalCalls", (long) sessionCount,
                "avgDurationMs", sessionCount > 0 ? sessionDurationMs / sessionCount : 0
        ));

        // Context compression stats from Micrometer
        double compressions = sumCounters("husky.context.compressions");
        result.put("contextStats", Map.of("totalCompressions", (long) compressions));

        // Approval stats from Micrometer
        Map<String, Long> approvalStats = new LinkedHashMap<>();
        for (Counter c : meterRegistry.find("husky.approval.requests").counters()) {
            String decision = c.getId().getTag("decision");
            if (decision != null) {
                approvalStats.put(decision, (long) c.count());
            }
        }
        if (!approvalStats.isEmpty()) {
            result.put("approvalStats", approvalStats);
        }

        return result;
    }

    /** 聚合所有 tag 变体的 counter 总数 */
    private double sumCounters(String meterName) {
        double total = 0;
        for (Counter c : meterRegistry.find(meterName).counters()) {
            total += c.count();
        }
        return total;
    }

    /** 聚合所有 tag 变体的 timer 总时长（ms） */
    private double sumTimerDuration(String meterName) {
        double totalMs = 0;
        for (Timer t : meterRegistry.find(meterName).timers()) {
            totalMs += t.totalTime(TimeUnit.MILLISECONDS);
        }
        return totalMs;
    }
}