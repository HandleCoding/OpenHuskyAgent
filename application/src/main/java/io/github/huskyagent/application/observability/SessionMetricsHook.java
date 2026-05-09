package io.github.huskyagent.application.observability;

import io.github.huskyagent.domain.hook.AfterHook;
import io.github.huskyagent.domain.hook.AgentHook;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.infra.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Session Metrics Hook — 将会话 token/duration 数据写入 SQLite sessions 表。
 *
 * <p>在 SESSION_END 事件触发时，从 HookContext 提取 observability 数据并持久化。</p>
 *
 * <p>默认启用，可通过 {@code husky.observability.metrics.enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "husky.observability.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class SessionMetricsHook implements AfterHook {

    private final SessionRepository sessionRepository;

    public SessionMetricsHook(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
        log.info("SessionMetricsHook initialized");
    }

    @Override
    public String name() {
        return "session-metrics";
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.SESSION_END);
    }

    @Override
    public void after(HookContext context) {
        Long inputTokens = context.getLong(HookDataKeys.SESSION_INPUT_TOKENS);
        Long outputTokens = context.getLong(HookDataKeys.SESSION_OUTPUT_TOKENS);
        Long durationMs = context.getLong(HookDataKeys.SESSION_DURATION_MS);

        if (inputTokens != null || outputTokens != null || durationMs != null) {
            sessionRepository.updateSessionObservability(
                    context.sessionId(),
                    inputTokens != null ? inputTokens.intValue() : 0,
                    outputTokens != null ? outputTokens.intValue() : 0,
                    durationMs != null ? durationMs : 0L
            );
            log.debug("Session observability data persisted: session={}, inputTokens={}, outputTokens={}, durationMs={}",
                    context.sessionId(), inputTokens, outputTokens, durationMs);
        }
    }
}