package io.github.huskyagent.application.agent;

public record ToolEvent(
        Type type,
        String toolName,
        String argsPreview,
        String toolArgs,
        long durationMs,
        String error
) {
    public enum Type { STARTED, COMPLETED, FAILED }

    public static ToolEvent started(String name, String preview) {
        return new ToolEvent(Type.STARTED, name, preview, null, 0, null);
    }

    public static ToolEvent started(String name, String preview, String toolArgs) {
        return new ToolEvent(Type.STARTED, name, preview, toolArgs, 0, null);
    }

    public static ToolEvent completed(String name, String preview, long durationMs) {
        return new ToolEvent(Type.COMPLETED, name, preview, null, durationMs, null);
    }

    public static ToolEvent completed(String name, String preview, String toolArgs, long durationMs) {
        return new ToolEvent(Type.COMPLETED, name, preview, toolArgs, durationMs, null);
    }

    public static ToolEvent failed(String name, String preview, long durationMs, String error) {
        return new ToolEvent(Type.FAILED, name, preview, null, durationMs, error);
    }

    public static ToolEvent failed(String name, String preview, String toolArgs, long durationMs, String error) {
        return new ToolEvent(Type.FAILED, name, preview, toolArgs, durationMs, error);
    }
}
