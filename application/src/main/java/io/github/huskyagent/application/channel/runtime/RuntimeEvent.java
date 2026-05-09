package io.github.huskyagent.application.channel.runtime;

import java.time.Instant;

public sealed interface RuntimeEvent permits ToolDisplayEvent {
    String sessionId();

    Instant timestamp();
}