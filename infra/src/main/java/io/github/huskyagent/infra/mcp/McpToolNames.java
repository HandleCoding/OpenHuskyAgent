package io.github.huskyagent.infra.mcp;

public final class McpToolNames {
    private static final String PREFIX = "mcp_";

    private McpToolNames() {
    }

    public static String prefixName(String serverName, String toolName) {
        return PREFIX + sanitize(serverName) + "_" + sanitize(toolName);
    }

    public static String serverName(String toolName) {
        if (toolName == null || !toolName.startsWith(PREFIX)) {
            return null;
        }
        int separator = toolName.indexOf('_', PREFIX.length());
        if (separator < 0) {
            return null;
        }
        return toolName.substring(PREFIX.length(), separator);
    }

    public static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
