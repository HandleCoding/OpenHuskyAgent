package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionEntity;
import org.springframework.stereotype.Component;

@Component
public class DefaultSessionAccessPolicy implements SessionAccessPolicy {

    @Override
    public boolean canResume(IsolationScope current, SessionEntity existing) {
        if (existing == null || !samePrincipal(current, existing)) {
            return false;
        }
        if (isLegacy(existing)) {
            return true;
        }
        return sameScene(current, existing) && sameChannel(current, existing) && sameConversationSource(current, existing);
    }

    @Override
    public boolean canList(IsolationScope current, SessionEntity existing) {
        if (existing == null || isLegacy(existing)) {
            return false;
        }
        return samePrincipal(current, existing) && sameScene(current, existing) && sameChannel(current, existing);
    }

    @Override
    public boolean canSearchMemory(IsolationScope current, SessionEntity existing) {
        return canList(current, existing);
    }

    private boolean isLegacy(SessionEntity existing) {
        return isBlank(existing.getOwnerPrincipalId())
                || isBlank(existing.getChannelType())
                || isBlank(existing.getSceneId());
    }

    private boolean samePrincipal(IsolationScope current, SessionEntity existing) {
        String owner = !isBlank(existing.getOwnerPrincipalId())
                ? existing.getOwnerPrincipalId()
                : existing.getUserId();
        return equals(current.getPrincipalId(), owner);
    }

    private boolean sameScene(IsolationScope current, SessionEntity existing) {
        return equals(current.getSceneId(), existing.getSceneId());
    }

    private boolean sameChannel(IsolationScope current, SessionEntity existing) {
        return equals(current.getChannelType(), existing.getChannelType());
    }

    private boolean sameConversationSource(IsolationScope current, SessionEntity existing) {
        return equalsNullable(current.getChatId(), existing.getSourceChatId())
                && equalsNullable(current.getThreadId(), existing.getSourceThreadId());
    }

    private boolean equals(String left, String right) {
        return !isBlank(left) && left.equals(right);
    }

    private boolean equalsNullable(String left, String right) {
        if (isBlank(left) && isBlank(right)) {
            return true;
        }
        return left != null && left.equals(right);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
