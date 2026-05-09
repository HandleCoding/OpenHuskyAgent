package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道事件 — Hook AfterHook 执行完毕后发布到 ChannelEventBus 的统一事件载体。
 *
 * @param sessionId 会话 ID（STARTUP/SHUTDOWN 事件可能为 null）
 * @param type      对应的 HookEvent 类型
 * @param data      事件数据，与 HookContext.data() 共享 HookDataKeys 常量
 * @param timestamp 事件产生时间
 */
public record ChannelEvent(
        String sessionId,
        HookEvent type,
        Map<String, Object> data,
        Instant timestamp
) {}
