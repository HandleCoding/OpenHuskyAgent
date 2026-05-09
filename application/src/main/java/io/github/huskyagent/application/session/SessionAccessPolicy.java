package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionEntity;

public interface SessionAccessPolicy {
    boolean canResume(IsolationScope current, SessionEntity existing);

    boolean canList(IsolationScope current, SessionEntity existing);

    boolean canSearchMemory(IsolationScope current, SessionEntity existing);
}
