package io.github.huskyagent.application.session;

public interface SessionKeyStrategy {
    String buildKey(IsolationScope scope);
}
