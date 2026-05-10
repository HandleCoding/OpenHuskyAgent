package io.github.huskyagent.service.channel.slack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SlackInboundDeduplicatorTest {

    @Test
    void scopesMessageIdsByPlatformAccountId() {
        MutableClock clock = new MutableClock();
        SlackInboundDeduplicator deduplicator = new SlackInboundDeduplicator(clock);

        assertFalse(deduplicator.isDuplicate("U1", "Ev1"));
        assertTrue(deduplicator.isDuplicate("U1", "Ev1"));
        assertFalse(deduplicator.isDuplicate("U2", "Ev1"));
    }

    @Test
    void expiresAfterTtl() {
        MutableClock clock = new MutableClock();
        SlackInboundDeduplicator deduplicator = new SlackInboundDeduplicator(clock);

        assertFalse(deduplicator.isDuplicate("U1", "Ev1"));
        clock.advance(SlackInboundDeduplicator.TTL.plusSeconds(1));

        assertFalse(deduplicator.isDuplicate("U1", "Ev1"));
    }

    @Test
    void missingMessageIdIsNotDeduped() {
        SlackInboundDeduplicator deduplicator = new SlackInboundDeduplicator();

        assertFalse(deduplicator.isDuplicate("U1", null));
        assertFalse(deduplicator.isDuplicate("U1", ""));
    }

    private static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-05-10T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
