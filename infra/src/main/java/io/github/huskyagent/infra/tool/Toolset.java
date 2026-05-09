package io.github.huskyagent.infra.tool;

public enum Toolset {

    CORE("core", "Core file operation tools"),

    TERMINAL("terminal", "Terminal command execution tools"),

    SEARCH("search", "File content search tools"),

    WEB("web", "Web search and content extraction tools"),

    BROWSER("browser", "Browser automation tools"),

    MEMORY("memory", "Persistent memory tools"),

    KNOWLEDGE("knowledge", "External knowledge retrieval and reading tools"),

    EXECUTE("execute", "Code execution sandbox tools"),

    DELEGATE("delegate", "Sub-agent delegation tools"),

    SKILLS("skills", "Skill discovery and loading tools"),

    MCP("mcp", "MCP client tools"),

    BUSINESS("business", "Business custom tools"),

    VISION("vision", "Image analysis tools");

    private final String name;
    private final String description;

    Toolset(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}