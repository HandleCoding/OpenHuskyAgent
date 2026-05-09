package io.github.huskyagent.infra.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import io.github.huskyagent.infra.config.BrowserConfig;
import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.http.ProxyResolver;
import io.github.huskyagent.infra.http.ProxySpec;
import io.github.huskyagent.infra.session.SessionContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class BrowserSessionManager {

    private final BrowserConfig config;
    private final AccessibilityTreeParser parser;
    private final ProxyResolver proxyResolver;
    private final WebConfig webConfig;
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private Playwright playwright;
    private Browser browser;

    public BrowserSession getOrCreate() {
        String sessionId = SessionContext.get();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "standalone";
        }
        cleanupIdleSessions();
        if (!sessions.containsKey(sessionId) && sessions.size() >= config.effectiveMaxSessions()) {
            throw new BrowserSessionException("Browser session limit reached: " + config.effectiveMaxSessions());
        }
        String key = sessionId;
        return sessions.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return new BrowserSession(config, parser, browser(), resolvedProxy(), resolvedNoProxy());
        });
    }

    public int sessionCount() {
        cleanupIdleSessions();
        return sessions.size();
    }

    public void cleanupIdleSessions() {
        long nowMillis = System.currentTimeMillis();
        Iterator<Map.Entry<String, BrowserSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BrowserSession> entry = iterator.next();
            BrowserSession session = entry.getValue();
            if (session.isClosed() || session.isIdle(nowMillis)) {
                session.close();
                iterator.remove();
            }
        }
    }

    private synchronized Browser browser() {
        if (browser != null && browser.isConnected()) {
            return browser;
        }
        if (playwright == null) {
            playwright = Playwright.create();
        }
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(config.isHeadless()));
        return browser;
    }

    @PreDestroy
    public synchronized void close() {
        sessions.values().forEach(BrowserSession::close);
        sessions.clear();
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    private String resolvedProxy() {
        WebConfig.ProxySettings webProxy = webConfig.getProxy();
        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        if (webProxy != null) {
            options.setEnabled(webProxy.getEnabled());
            options.setUrl(webProxy.getUrl());
            options.setNoProxy(webProxy.getNoProxy());
        }
        Optional<ProxySpec> spec = proxyResolver.resolve(URI.create("https://example.com"), options);
        return spec.map(s -> s.scheme() + "://" + s.host() + ":" + s.port()).orElse(null);
    }

    private String resolvedNoProxy() {
        WebConfig.ProxySettings webProxy = webConfig.getProxy();
        if (webProxy != null && webProxy.getNoProxy() != null && !webProxy.getNoProxy().isBlank()) {
            return webProxy.getNoProxy();
        }
        String globalNoProxy = System.getenv("NO_PROXY");
        if (globalNoProxy == null) {
            globalNoProxy = System.getenv("no_proxy");
        }
        return globalNoProxy;
    }

    public static class BrowserSessionException extends RuntimeException {
        public BrowserSessionException(String message) {
            super(message);
        }
    }
}
