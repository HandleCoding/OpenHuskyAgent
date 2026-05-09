package io.github.huskyagent.domain.event;

import io.github.huskyagent.domain.hook.HookEvent;

import java.time.Instant;
import java.util.Map;

public record ChannelEvent(
        String sessionId,
        HookEvent type,
        Map<String, Object> data,
        Instant timestamp
) {}
