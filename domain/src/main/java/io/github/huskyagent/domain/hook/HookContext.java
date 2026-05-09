package io.github.huskyagent.domain.hook;

import java.util.HashMap;
import java.util.Map;

public record HookContext(
        HookEvent event,
        String sessionId,
        Map<String, Object> data
) {

    public <T> T get(String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        return null;
    }

    public String getString(String key) {
        return get(key, String.class);
    }

    public Long getLong(String key) {
        Object value = data.get(key);
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    public Boolean getBoolean(String key) {
        return get(key, Boolean.class);
    }

    public HookContext with(String key, Object value) {
        Map<String, Object> newData = new HashMap<>(data);
        newData.put(key, value);
        return new HookContext(event, sessionId, newData);
    }
}
