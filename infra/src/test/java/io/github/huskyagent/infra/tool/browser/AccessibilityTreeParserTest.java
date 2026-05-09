package io.github.huskyagent.infra.tool.browser;

import io.github.huskyagent.infra.config.BrowserConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccessibilityTreeParserTest {

    @Test
    void buildsCompactSnapshotWithRefs() {
        BrowserConfig config = new BrowserConfig();
        AccessibilityTreeParser parser = new AccessibilityTreeParser(config);
        BrowserSession.PageSnapshot page = new BrowserSession.PageSnapshot(
            "Login",
            "https://example.test/login",
            "Sign in to continue Email Password Sign in",
            List.of(
                Map.of("ref", "e1", "tag", "input", "type", "text", "label", "Email"),
                Map.of("ref", "e2", "tag", "button", "label", "Sign in")
            )
        );

        AccessibilityTreeParser.Snapshot snapshot = parser.buildSnapshot(page, false);

        assertTrue(snapshot.text().contains("Title: Login"));
        assertTrue(snapshot.text().contains("[@e1] input[text] \"Email\""));
        assertTrue(snapshot.text().contains("[@e2] button \"Sign in\""));
        assertEquals("e1", snapshot.refs().get("@e1"));
        assertEquals("e2", snapshot.refs().get("@e2"));
    }

    @Test
    void truncatesLongSnapshot() {
        BrowserConfig config = new BrowserConfig();
        config.setCompactSnapshotMaxChars(1000);
        AccessibilityTreeParser parser = new AccessibilityTreeParser(config);
        BrowserSession.PageSnapshot page = new BrowserSession.PageSnapshot(
            "Long",
            "https://example.test",
            "x".repeat(5000),
            List.of()
        );

        AccessibilityTreeParser.Snapshot snapshot = parser.buildSnapshot(page, false);

        assertTrue(snapshot.text().contains("[truncated; call browser_snapshot with full=true or scroll]"));
    }
}
