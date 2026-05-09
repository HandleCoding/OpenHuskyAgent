package io.github.huskyagent.infra.memory;

/**
 * Memory Provider 接口
 *
 * 定义记忆提供者的标准契约，支持多种存储后端：
 * - BuiltinMemoryProvider: 文件存储 (MEMORY.md/USER.md)
 * - SessionMemoryProvider: Hybrid FTS5+LIKE 搜索
 * - VectorMemoryProvider: 向量数据库（可选）
 *
 * 工具暴露由配套的 ToolProvider 实现（如 BuiltinMemoryToolProvider），不在此接口中定义。
 */
public interface MemoryProvider {

    /**
     * 获取 Provider 名称
     */
    String getName();

    /**
     * 检查 Provider 是否可用
     */
    boolean isAvailable();

    /**
     * 初始化 Provider
     *
     * @param context 初始化上下文，包含 sessionId、workingDirectory 等
     */
    void initialize(MemoryContext context);

    /**
     * 构建系统提示（Frozen Snapshot）
     *
     * 用于注入到 PromptBuilder，内容在会话开始时冻结
     * 后续写入操作不会改变已注入的提示（保护 prefix cache）
     */
    String buildSystemPrompt();

    default String buildSystemPrompt(String promptMode) {
        return buildSystemPrompt();
    }

    /**
     * 动态检索记忆
     *
     * 根据查询词检索相关记忆，返回 TopK 结果
     *
     * @param query 查询词
     * @param options 检索选项
     * @return 检索结果
     */
    MemoryResult prefetch(String query, MemorySearchOptions options);

    /**
     * 同步对话轮次
     *
     * 将完成的对话轮次同步到存储（可选实现）
     *
     * @param user 用户消息
     * @param assistant 助手消息
     */
    default void syncTurn(String user, String assistant) {
        // 默认空实现
    }
}
