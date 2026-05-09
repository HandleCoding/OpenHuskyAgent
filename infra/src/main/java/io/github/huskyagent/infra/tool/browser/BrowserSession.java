package io.github.huskyagent.infra.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import io.github.huskyagent.infra.config.BrowserConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class BrowserSession implements AutoCloseable {

    private static final String SNAPSHOT_SCRIPT = """
        () => {
          const old = document.querySelectorAll('[data-husky-ref]');
          old.forEach((el) => el.removeAttribute('data-husky-ref'));

          const isVisible = (el) => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style && style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
          };

          const textOf = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
          const labelOf = (el) => {
            const aria = el.getAttribute('aria-label');
            if (aria) return aria;
            const labelledBy = el.getAttribute('aria-labelledby');
            if (labelledBy) {
              const label = labelledBy.split(/\s+/).map((id) => document.getElementById(id)?.innerText || '').join(' ').trim();
              if (label) return label;
            }
            if (el.labels && el.labels.length) return Array.from(el.labels).map((label) => textOf(label)).join(' ').trim();
            const title = el.getAttribute('title');
            if (title) return title;
            const placeholder = el.getAttribute('placeholder');
            if (placeholder) return placeholder;
            return textOf(el);
          };

          const selector = [
            'a[href]', 'button', 'input', 'textarea', 'select',
            '[role]', '[tabindex]:not([tabindex="-1"])', '[contenteditable="true"]',
            'summary', 'label'
          ].join(',');

          const elements = Array.from(document.querySelectorAll(selector))
            .filter((el) => isVisible(el))
            .filter((el) => !el.disabled && el.getAttribute('aria-hidden') !== 'true')
            .slice(0, 200);

          const mapped = elements.map((el, i) => {
            const ref = `e${i + 1}`;
            el.setAttribute('data-husky-ref', ref);
            return {
              ref,
              tag: el.tagName.toLowerCase(),
              role: el.getAttribute('role') || '',
              type: el.getAttribute('type') || '',
              label: labelOf(el),
              text: textOf(el),
              placeholder: el.getAttribute('placeholder') || '',
              title: el.getAttribute('title') || ''
            };
          });

          return {
            title: document.title || '',
            url: window.location.href,
            visibleText: (document.body ? textOf(document.body) : ''),
            elements: mapped
          };
        }
        """;

    private final BrowserConfig config;
    private final AccessibilityTreeParser parser;
    private final BrowserContext context;
    private final Page page;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Instant lastAccessed = Instant.now();
    private volatile Map<String, String> refs = Map.of();

    BrowserSession(BrowserConfig config, AccessibilityTreeParser parser, Browser browser, String proxyServer, String proxyBypass) {
        this.config = config;
        this.parser = parser;
        Browser.NewContextOptions opts = new Browser.NewContextOptions()
            .setViewportSize(config.getViewportWidth(), config.getViewportHeight());
        if (proxyServer != null && !proxyServer.isBlank()) {
            Proxy proxy = new Proxy(proxyServer);
            if (proxyBypass != null && !proxyBypass.isBlank()) {
                proxy.setBypass(proxyBypass);
            }
            opts.setProxy(proxy);
        }
        this.context = browser.newContext(opts);
        this.page = context.newPage();
        this.page.setDefaultTimeout(config.timeoutMillis());
        this.page.setDefaultNavigationTimeout(config.timeoutMillis());
    }

    public synchronized String navigate(String url) {
        touch();
        page.navigate(url, new Page.NavigateOptions().setTimeout(config.timeoutMillis()).setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        waitForSettledPage();
        return snapshot(false);
    }

    public synchronized String snapshot(boolean full) {
        touch();
        ensurePageOpen();
        Object raw = page.evaluate(SNAPSHOT_SCRIPT);
        PageSnapshot pageSnapshot = PageSnapshot.from(raw);
        AccessibilityTreeParser.Snapshot snapshot = parser.buildSnapshot(pageSnapshot, full);
        refs = snapshot.refs();
        return snapshot.text();
    }

    public synchronized String click(String ref) {
        touch();
        Locator locator = locatorForRef(ref);
        locator.click(new Locator.ClickOptions().setTimeout(config.timeoutMillis()));
        waitForSettledPage();
        return snapshot(false);
    }

    public synchronized String type(String ref, String text) {
        touch();
        Locator locator = locatorForRef(ref);
        locator.fill("", new Locator.FillOptions().setTimeout(config.timeoutMillis()));
        locator.type(text == null ? "" : text, new Locator.TypeOptions().setTimeout(config.timeoutMillis()));
        waitForSettledPage();
        return snapshot(false);
    }

    public synchronized String scroll(String direction) {
        touch();
        int multiplier = "up".equalsIgnoreCase(direction) ? -1 : 1;
        page.evaluate("amount => window.scrollBy(0, amount)", multiplier * Math.max(300, config.getViewportHeight() * 3 / 4));
        waitForSettledPage();
        return snapshot(false);
    }

    public synchronized String press(String key) {
        touch();
        page.keyboard().press(key);
        waitForSettledPage();
        return snapshot(false);
    }

    public synchronized String back() {
        touch();
        page.goBack(new Page.GoBackOptions().setTimeout(config.timeoutMillis()).setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        waitForSettledPage();
        return snapshot(false);
    }

    public boolean isIdle(long nowMillis) {
        return nowMillis - lastAccessed.toEpochMilli() > config.sessionIdleTimeoutMillis();
    }

    public boolean isClosed() {
        return closed.get() || page.isClosed();
    }

    private Locator locatorForRef(String ref) {
        ensurePageOpen();
        String normalized = normalizeRef(ref);
        String domRef = refs.get(normalized);
        if (domRef == null) {
            throw new UnknownBrowserRefException(normalized);
        }
        Locator locator = page.locator("[data-husky-ref='" + domRef + "']");
        if (locator.count() == 0) {
            throw new UnknownBrowserRefException(normalized);
        }
        return locator.first();
    }

    static String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new UnknownBrowserRefException("(blank)");
        }
        String trimmed = ref.trim();
        return trimmed.startsWith("@") ? trimmed : "@" + trimmed;
    }

    private void waitForSettledPage() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(Math.min(2000, config.timeoutMillis())));
        } catch (PlaywrightException ignored) {
            // Some interactions update the DOM without navigation; a timeout here is not a tool failure.
        }
    }

    private void ensurePageOpen() {
        if (isClosed()) {
            throw new PlaywrightException("Browser page is closed");
        }
    }

    private void touch() {
        lastAccessed = Instant.now();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            context.close();
        } catch (Exception ignored) {
        }
    }

    public record PageSnapshot(String title, String url, String visibleText, List<Map<String, Object>> elements) {
        @SuppressWarnings("unchecked")
        static PageSnapshot from(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return new PageSnapshot("", "", "", List.of());
            }
            return new PageSnapshot(
                stringValue(map.get("title")),
                stringValue(map.get("url")),
                stringValue(map.get("visibleText")),
                map.get("elements") instanceof List<?> elements ? (List<Map<String, Object>>) (List<?>) elements : List.of()
            );
        }

        private static String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }

    public static class UnknownBrowserRefException extends RuntimeException {
        public UnknownBrowserRefException(String ref) {
            super("Unknown browser ref: " + ref);
        }
    }
}
