package io.github.huskyagent.application.session;

import org.springframework.stereotype.Component;

import java.util.StringJoiner;

@Component
public class DefaultSessionKeyStrategy implements SessionKeyStrategy {

    @Override
    public String buildKey(IsolationScope scope) {
        StringJoiner joiner = new StringJoiner("|");
        add(joiner, "principal", scope.getPrincipalId());
        add(joiner, "channel", scope.getChannelType());
        add(joiner, "scene", scope.getSceneId());
        add(joiner, "conversation", scope.getConversationType());
        add(joiner, "account", scope.getPlatformAccountId());
        add(joiner, "chat", scope.getChatId());
        add(joiner, "thread", scope.getThreadId());
        return joiner.toString();
    }

    private void add(StringJoiner joiner, String key, String value) {
        joiner.add(key + "=" + normalize(value));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
