package io.github.huskyagent.infra.tool.browser;

import io.github.huskyagent.infra.config.BrowserConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserSessionManagerTest {

    @Test
    void normalizeRefAcceptsBareRefs() {
        assertEquals("@e3", BrowserSession.normalizeRef("e3"));
        assertEquals("@e3", BrowserSession.normalizeRef("@e3"));
    }

    @Test
    void normalizeRefRejectsBlankRefs() {
        assertThrows(BrowserSession.UnknownBrowserRefException.class, () -> BrowserSession.normalizeRef(" "));
    }

    @Test
    void browserConfigBoundsValues() {
        BrowserConfig config = new BrowserConfig();
        config.setMaxSessions(0);
        config.setSnapshotMaxChars(1);
        config.setCompactSnapshotMaxChars(1);

        assertEquals(1, config.effectiveMaxSessions());
        assertEquals(1000, config.effectiveSnapshotMaxChars(true));
        assertEquals(1000, config.effectiveSnapshotMaxChars(false));
    }
}
