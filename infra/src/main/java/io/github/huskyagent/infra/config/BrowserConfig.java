package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "browser")
public class BrowserConfig {

    private boolean enabled = false;

    private boolean headless = true;

    private int timeoutSeconds = 120;

    private int snapshotMaxChars = 100_000;

    private int compactSnapshotMaxChars = 20_000;

    private int sessionIdleTimeoutSeconds = 600;

    private int maxSessions = 16;

    private int viewportWidth = 1280;

    private int viewportHeight = 900;

    private boolean allowLocalhost = true;

    private boolean allowPrivateNetwork = false;

    public double timeoutMillis() {
        return Math.max(1, timeoutSeconds) * 1000.0;
    }

    public long sessionIdleTimeoutMillis() {
        return Math.max(1, sessionIdleTimeoutSeconds) * 1000L;
    }

    public int effectiveSnapshotMaxChars(boolean full) {
        int max = full ? snapshotMaxChars : compactSnapshotMaxChars;
        return Math.max(1000, max);
    }

    public int effectiveMaxSessions() {
        return Math.max(1, maxSessions);
    }
}
