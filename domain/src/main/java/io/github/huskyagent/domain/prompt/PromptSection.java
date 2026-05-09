package io.github.huskyagent.domain.prompt;

/**
 * Prompt Section 接口
 *
 * 每个 Section 代表系统提示的一个组成部分，
 * 可以独立配置、启用/禁用、排序。
 */
public interface PromptSection {

    /**
     * Section 名称（用于日志和调试）
     */
    String getName();

    /**
     * Section 优先级（数字越小越先加载）
     *
     * 建议范围：
     * - 0-99: 核心身份（Identity）
     * - 100-199: 用户/Gateway 提示
     * - 200-299: 持久化内存（Memory）
     * - 300-399: Skills
     * - 400-499: 上下文文件
     * - 500-599: 工具说明
     * - 600-699: MCP/Skill Tools
     * - 800-899: 运行时环境（OS、模型、Provider 等）
     * - 900-999: 其他元信息
     */
    int getPriority();

    /**
     * Section 是否启用
     */
    boolean isEnabled();

    /**
     * 生成 Section 内容
     *
     * @param context 组装上下文（包含 sessionId、用户消息等）
     * @return Section 文本内容，如果不需要输出可返回空字符串
     */
    String build(PromptContext context);

    /**
     * Section 是否需要在每次对话时重建
     *
     * - true: 每次对话都重建（如 DateTime）
     * - false: 会话级别缓存（如 Identity）
     */
    default boolean isDynamic() {
        return false;
    }
}
