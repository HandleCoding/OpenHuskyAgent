package io.github.huskyagent.infra.tool.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.PlaywrightException;
import io.github.huskyagent.infra.config.BrowserConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.web.UrlSafety;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserToolProvider implements ToolProvider {

    private final BrowserConfig config;
    private final BrowserSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ToolDefinition> getTools() {
        if (!config.isEnabled()) {
            return List.of();
        }
        log.info("Registered browser tools");
        return List.of(
            navigateTool(),
            snapshotTool(),
            clickTool(),
            typeTool(),
            scrollTool(),
            pressTool(),
            backTool()
        );
    }

    private ToolDefinition navigateTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        stringProperty(props, "url", "URL to open in the browser. If no scheme is supplied, https:// is used.");
        required(schema, "url");
        return definition("browser_navigate",
            "Navigate the browser to a URL and return a compact accessibility snapshot with @eN element refs.",
            schema,
            this::handleNavigate);
    }

    private ToolDefinition snapshotTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        ObjectNode full = props.putObject("full");
        full.put("type", "boolean");
        full.put("description", "Whether to include broader visible page text. Defaults to false.");
        full.put("default", false);
        return definition("browser_snapshot",
            "Return the current browser page snapshot. Use refs from the latest snapshot for click/type.",
            schema,
            this::handleSnapshot);
    }

    private ToolDefinition clickTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        stringProperty(props, "ref", "Element ref from the latest browser snapshot, for example @e3.");
        required(schema, "ref");
        return definition("browser_click",
            "Click an element by @eN ref from the latest browser snapshot and return a refreshed snapshot.",
            schema,
            this::handleClick);
    }

    private ToolDefinition typeTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        stringProperty(props, "ref", "Input element ref from the latest browser snapshot, for example @e2.");
        stringProperty(props, "text", "Text to enter. The field is cleared first.");
        required(schema, "ref", "text");
        return definition("browser_type",
            "Clear and type text into an input-like element by @eN ref, then return a refreshed snapshot.",
            schema,
            this::handleType);
    }

    private ToolDefinition scrollTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        ObjectNode direction = props.putObject("direction");
        direction.put("type", "string");
        direction.put("description", "Scroll direction.");
        ArrayNode values = direction.putArray("enum");
        values.add("up");
        values.add("down");
        required(schema, "direction");
        return definition("browser_scroll",
            "Scroll the browser viewport up or down and return a refreshed snapshot.",
            schema,
            this::handleScroll);
    }

    private ToolDefinition pressTool() {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        stringProperty(props, "key", "Keyboard key to press, such as Enter, Tab, Escape, ArrowDown.");
        required(schema, "key");
        return definition("browser_press",
            "Press a keyboard key on the current page and return a refreshed snapshot.",
            schema,
            this::handlePress);
    }

    private ToolDefinition backTool() {
        ObjectNode schema = objectSchema();
        schema.putObject("properties");
        return definition("browser_back",
            "Go back in browser history and return a refreshed snapshot.",
            schema,
            this::handleBack);
    }

    public ToolResult handleNavigate(Map<String, Object> args) {
        String url = stringArg(args, "url");
        if (url.isBlank()) {
            return ToolResult.failure("url is required", false, "Provide a URL to open");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("data:")) {
            url = "https://" + url;
        }
        String safetyError = validateUrl(url);
        if (safetyError != null) {
            return ToolResult.failure(safetyError, false, "Use a public URL or adjust browser URL policy for local testing");
        }
        String targetUrl = url;
        return execute(() -> sessionManager.getOrCreate().navigate(targetUrl));
    }

    public ToolResult handleSnapshot(Map<String, Object> args) {
        boolean full = Boolean.TRUE.equals(args.get("full"));
        return execute(() -> sessionManager.getOrCreate().snapshot(full));
    }

    public ToolResult handleClick(Map<String, Object> args) {
        String ref = stringArg(args, "ref");
        if (ref.isBlank()) {
            return ToolResult.failure("ref is required", true, "Call browser_snapshot and pass an @eN ref");
        }
        return execute(() -> sessionManager.getOrCreate().click(ref));
    }

    public ToolResult handleType(Map<String, Object> args) {
        String ref = stringArg(args, "ref");
        if (ref.isBlank()) {
            return ToolResult.failure("ref is required", true, "Call browser_snapshot and pass an input @eN ref");
        }
        return execute(() -> sessionManager.getOrCreate().type(ref, stringArg(args, "text")));
    }

    public ToolResult handleScroll(Map<String, Object> args) {
        String direction = stringArg(args, "direction").toLowerCase(Locale.ROOT);
        if (!"up".equals(direction) && !"down".equals(direction)) {
            return ToolResult.failure("direction must be 'up' or 'down'", true, "Use direction=up or direction=down");
        }
        return execute(() -> sessionManager.getOrCreate().scroll(direction));
    }

    public ToolResult handlePress(Map<String, Object> args) {
        String key = stringArg(args, "key");
        if (key.isBlank()) {
            return ToolResult.failure("key is required", true, "Provide a key such as Enter, Tab, Escape, or ArrowDown");
        }
        return execute(() -> sessionManager.getOrCreate().press(key));
    }

    public ToolResult handleBack(Map<String, Object> args) {
        return execute(() -> sessionManager.getOrCreate().back());
    }

    private ToolResult execute(BrowserAction action) {
        try {
            return ToolResult.success(action.run());
        } catch (BrowserSession.UnknownBrowserRefException e) {
            return ToolResult.failure(e.getMessage(), true, "Call browser_snapshot again and use one of the returned @eN refs");
        } catch (BrowserSessionManager.BrowserSessionException e) {
            return ToolResult.failure(e.getMessage(), true, "Close idle sessions or increase browser.max-sessions");
        } catch (PlaywrightException e) {
            String message = e.getMessage() == null ? "Browser operation failed" : e.getMessage();
            if (isMissingBrowserRuntime(message)) {
                return ToolResult.failure(missingBrowserRuntimeMessage(), false, "Run husky browser install, restart Husky, then try the browser tool again");
            }
            return ToolResult.failure(message, true, "Try browser_snapshot, browser_back, or browser_navigate again");
        } catch (Exception e) {
            log.error("browser tool failed: {}", e.getMessage(), e);
            return ToolResult.failure("Browser operation failed: " + e.getMessage(), true, "Try browser_snapshot or browser_navigate again");
        }
    }

    private boolean isMissingBrowserRuntime(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("executable doesn't exist")
                || lower.contains("browser executable not found")
                || lower.contains("playwright install")
                || lower.contains("install chromium");
    }

    private String missingBrowserRuntimeMessage() {
        return "Browser runtime is not installed. Run `husky browser install`, restart Husky, then try the browser tool again. "
                + "If you are running from a source checkout, you can also run `./mvnw -B -ntp exec:java -pl infra "
                + "-Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"`.";
    }

    private String validateUrl(String url) {
        if (url.startsWith("data:")) {
            return null;
        }
        if (UrlSafety.containsSecret(url)) {
            return "Blocked: URL contains what appears to be an API key or token";
        }
        if (UrlSafety.isSafeUrl(url)) {
            return null;
        }
        if (isAllowedLocalhost(url)) {
            return null;
        }
        if (config.isAllowPrivateNetwork() && isResolvableHost(url)) {
            return null;
        }
        return "Blocked: URL targets a private or internal network address";
    }

    private boolean isAllowedLocalhost(String url) {
        if (!config.isAllowLocalhost()) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower) || lower.endsWith(".localhost");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isResolvableHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            InetAddress.getAllByName(host);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ToolDefinition definition(String name, String description, ObjectNode schema, java.util.function.Function<Map<String, Object>, ToolResult> handler) {
        return ToolDefinition.of(name, description, Toolset.BROWSER, schema, handler)
                .withEmoji("\uD83C\uDF10")
                .withMaxResultSize(config.getSnapshotMaxChars())
                .withTimeout(args -> Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    private void stringProperty(ObjectNode props, String name, String description) {
        ObjectNode node = props.putObject(name);
        node.put("type", "string");
        node.put("description", description);
    }

    private void required(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) {
            required.add(name);
        }
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    @FunctionalInterface
    private interface BrowserAction {
        String run();
    }
}
