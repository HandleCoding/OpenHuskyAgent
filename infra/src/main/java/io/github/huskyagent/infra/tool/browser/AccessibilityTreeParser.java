package io.github.huskyagent.infra.tool.browser;

import io.github.huskyagent.infra.config.BrowserConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccessibilityTreeParser {

    private final BrowserConfig config;

    public Snapshot buildSnapshot(BrowserSession.PageSnapshot pageSnapshot, boolean full) {
        List<Map<String, Object>> elements = pageSnapshot.elements();
        List<String> lines = new ArrayList<>();
        Map<String, String> refs = new LinkedHashMap<>();

        lines.add("Title: " + blankToPlaceholder(pageSnapshot.title()));
        lines.add("URL: " + blankToPlaceholder(pageSnapshot.url()));

        if (!pageSnapshot.visibleText().isBlank()) {
            lines.add("");
            lines.add("Visible text:");
            lines.add(limitText(pageSnapshot.visibleText(), full ? 8000 : 2500));
        }

        lines.add("");
        lines.add("Interactive elements:");
        if (elements.isEmpty()) {
            lines.add("(none found)");
        }

        int index = 1;
        for (Map<String, Object> element : elements) {
            String ref = "@e" + index;
            String domRef = "e" + index;
            refs.put(ref, domRef);

            String tag = value(element, "tag").toLowerCase(Locale.ROOT);
            String role = value(element, "role");
            String type = value(element, "type");
            String label = firstNonBlank(value(element, "label"), value(element, "text"), value(element, "placeholder"), value(element, "title"));
            String descriptor = role.isBlank() ? tag : role;
            if (!type.isBlank() && ("input".equals(tag) || "button".equals(tag))) {
                descriptor += "[" + type + "]";
            }
            lines.add("[" + ref + "] " + descriptor + formatQuoted(label));
            index++;
        }

        String text = truncate(String.join("\n", lines), config.effectiveSnapshotMaxChars(full));
        return new Snapshot(text, refs);
    }

    private String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return normalizeWhitespace(value);
            }
        }
        return "";
    }

    private String formatQuoted(String value) {
        if (value.isBlank()) {
            return "";
        }
        return " \"" + value.replace("\"", "\\\"") + "\"";
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value.trim();
    }

    private String limitText(String text, int maxChars) {
        String normalized = normalizeWhitespace(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "... [visible text truncated]";
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n[truncated; call browser_snapshot with full=true or scroll]";
    }

    public record Snapshot(String text, Map<String, String> refs) {}
}
