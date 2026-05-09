package io.github.huskyagent.infra.tool.browser;

import com.microsoft.playwright.PlaywrightException;
import io.github.huskyagent.infra.config.BrowserConfig;
import io.github.huskyagent.infra.config.ProxyProperties;
import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.http.NoProxyMatcher;
import io.github.huskyagent.infra.http.ProxyResolver;
import io.github.huskyagent.infra.session.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("browser")
class BrowserSessionIntegrationTest {

    private BrowserSessionManager manager;

    @AfterEach
    void tearDown() {
        SessionContext.clear();
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void navigatesTypesClicksAndSnapshotsDataUrl() {
        BrowserConfig config = new BrowserConfig();
        config.setEnabled(true);
        config.setHeadless(true);
        config.setTimeoutSeconds(5);
        AccessibilityTreeParser parser = new AccessibilityTreeParser(config);
        manager = new BrowserSessionManager(config, parser, new ProxyResolver(new ProxyProperties(), new NoProxyMatcher()), new WebConfig());
        SessionContext.set("browser-it");

        String html = """
            <!doctype html>
            <html><head><title>Browser IT</title></head>
            <body>
              <input aria-label='Name' id='name'>
              <button onclick="document.getElementById('out').innerText='Hello ' + document.getElementById('name').value">Submit</button>
              <p id='out'></p>
            </body></html>
            """;
        String url = "data:text/html," + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");

        try {
            BrowserSession session = manager.getOrCreate();
            String snapshot = session.navigate(url);
            assertTrue(snapshot.contains("Title: Browser IT"));
            assertTrue(snapshot.contains("[@e1] input"));
            assertTrue(snapshot.contains("[@e2] button"));

            session.type("@e1", "Husky");
            String afterClick = session.click("@e2");
            assertTrue(afterClick.contains("Hello Husky"));
        } catch (PlaywrightException e) {
            Assumptions.abort("Playwright browser binaries are not installed: " + e.getMessage());
        }
    }
}
