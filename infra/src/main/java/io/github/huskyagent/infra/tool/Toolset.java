package io.github.huskyagent.infra.tool;

/**
 * 工具分组
 * 参考 Hermes toolsets.py 的设计
 */
public enum Toolset {

    /**
     * 核心工具 - 基础文件操作
     */
    CORE("core", "核心文件操作工具"),

    /**
     * 终端工具 - 命令执行
     */
    TERMINAL("terminal", "终端命令执行工具"),

    /**
     * 搜索工具 - 内容搜索
     */
    SEARCH("search", "文件内容搜索工具"),

    /**
     * 网络工具 - Web 搜索/提取
     */
    WEB("web", "Web 搜索和内容提取工具"),

    /**
     * 浏览器工具 - 网页自动化
     */
    BROWSER("browser", "浏览器自动化工具"),

    /**
     * 记忆工具 - 持久化记忆
     */
    MEMORY("memory", "持久化记忆工具"),

    /**
     * 知识工具 - 外部资料检索和读取
     */
    KNOWLEDGE("knowledge", "外部知识检索和读取工具"),

    /**
     * 执行工具 - 代码沙箱
     */
    EXECUTE("execute", "代码执行沙箱工具"),

    /**
     * 委派工具 - 子 Agent
     */
    DELEGATE("delegate", "子 Agent 委派工具"),

    /**
     * 技能工具 - 技能发现和加载
     */
    SKILLS("skills", "技能发现和加载工具"),

    /**
     * MCP 工具 - 动态加载
     */
    MCP("mcp", "MCP 客户端工具"),

    /**
     * 业务工具 - 自定义扩展
     */
    BUSINESS("business", "业务自定义工具"),

    /**
     * 视觉工具 - 图像分析
     */
    VISION("vision", "图像分析工具");

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