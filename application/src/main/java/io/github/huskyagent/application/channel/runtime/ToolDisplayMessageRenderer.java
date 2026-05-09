package io.github.huskyagent.application.channel.runtime;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ToolDisplayMessageRenderer {

    private static final int MAX_PREVIEW_CHARS = 160;
    private static final int MAX_ERROR_CHARS = 200;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(token|password|secret|api[_-]?key|authorization)(\\s*[:=]\\s*)([^,}\\s]+)"
    );

    public String render(ToolDisplayEvent event) {
        if (event == null || event.toolName() == null || event.toolName().isBlank()) {
            return null;
        }
        return switch (event.status()) {
            case STARTED -> renderStarted(event);
            case COMPLETED -> "Tool completed: " + event.toolName() + renderDuration(event.durationMs());
            case FAILED -> renderFailed(event);
        };
    }

    private String renderStarted(ToolDisplayEvent event) {
        String preview = clean(event.argsPreview());
        if (preview == null || preview.isBlank()) {
            return "Calling tool: " + event.toolName();
        }
        return "Calling tool: " + event.toolName() + "\nArguments: " + truncate(redact(preview), MAX_PREVIEW_CHARS);
    }

    private String renderFailed(ToolDisplayEvent event) {
        String message = "Tool failed: " + event.toolName() + renderDuration(event.durationMs());
        String error = clean(event.error());
        if (error == null || error.isBlank()) {
            return message;
        }
        return message + "\nReason: " + truncate(redact(error), MAX_ERROR_CHARS);
    }

    private String renderDuration(long durationMs) {
        if (durationMs <= 0) {
            return "";
        }
        if (durationMs < 1000) {
            return " (" + durationMs + "ms)";
        }
        double seconds = durationMs / 1000.0;
        return " (" + String.format(Locale.ROOT, "%.1fs", seconds) + ")";
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String redact(String value) {
        return SECRET_PATTERN.matcher(value).replaceAll("$1$2***");
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 3) + "...";
    }
}
