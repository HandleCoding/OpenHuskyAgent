package io.github.huskyagent.service.channel.slack;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SlackInboundDeduplicator {

    static final Duration TTL = Duration.ofHours(2);
    static final int MAX_ENTRIES = 8192;

    private final Clock clock;
    private final Map<String, Long> seenAtByKey = new LinkedHashMap<>();

    public SlackInboundDeduplicator() {
        this(Clock.systemUTC());
    }

    SlackInboundDeduplicator(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isDuplicate(String platformAccountId, String messageId) {
        if (isBlank(messageId)) {
            return false;
        }
        long now = clock.millis();
        prune(now);
        String key = key(platformAccountId, messageId);
        Long seenAt = seenAtByKey.get(key);
        if (seenAt != null && now - seenAt < TTL.toMillis()) {
            return true;
        }
        seenAtByKey.put(key, now);
        trimToMaxEntries();
        return false;
    }

    int size() {
        return seenAtByKey.size();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> iterator = seenAtByKey.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() >= TTL.toMillis()) {
                iterator.remove();
            }
        }
    }

    private void trimToMaxEntries() {
        Iterator<String> iterator = seenAtByKey.keySet().iterator();
        while (seenAtByKey.size() > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private String key(String platformAccountId, String messageId) {
        return (isBlank(platformAccountId) ? "unknown" : platformAccountId) + ":" + messageId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
