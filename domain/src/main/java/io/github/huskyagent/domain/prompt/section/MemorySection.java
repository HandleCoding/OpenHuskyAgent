package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.memory.MemoryManager;

/**
 * Memory Section
 *
 * 注入持久化记忆内容（MEMORY.md + USER.md）
 *
 * 使用 MemoryManager 协调多个 Provider：
 * - BuiltinMemoryProvider: 静态文件记忆
 * - SessionMemoryProvider: 会话历史（通过工具检索）
 * - VectorMemoryProvider: 向量检索（可选）
 */
public class MemorySection extends AbstractPromptSection {

    private final MemoryManager memoryManager;

    // 兼容旧 API（用于测试）
    private String memoryContent;
    private String userContent;

    public MemorySection() {
        this.memoryManager = null;
    }

    public MemorySection(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public int getPriority() {
        return 200;  // Identity 之后
    }

    @Override
    public String build(PromptContext context) {
        if (!context.getRuntimePolicy().getMemoryPolicy().isEnabled()
                || context.getRuntimePolicy().getMemoryPolicy().getPromptMode() == SceneConfig.MemoryPromptMode.NONE) {
            return "";
        }

        // 如果有 MemoryManager，使用它构建系统提示（Frozen Snapshot）
        if (memoryManager != null && memoryManager.hasAvailableProvider()) {
            var sessionScope = context.getSessionScope()
                    .orElseThrow(() -> new IllegalStateException("SessionScope is required for memory prompt"));
            String prompt = memoryManager.buildSystemPrompt(sessionScope);
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }

        // 回退到原有的简单实现
        return buildFallback(context);
    }

    /**
     * 回退实现（不使用 MemoryManager）
     */
    private String buildFallback(PromptContext context) {
        StringBuilder sb = new StringBuilder();

        // 优先从 context 获取，其次使用成员变量
        String mem = context.getMemoryContent().orElse(memoryContent);
        String user = context.getUserContent().orElse(userContent);

        if (mem != null && !mem.isBlank()) {
            sb.append(buildWithTag("memory-context", """
                [System note: The following is recalled memory context, NOT new user input]

                ### Agent Notes (MEMORY.md)
                """ + mem));
        }

        if (user != null && !user.isBlank()) {
            sb.append(buildWithTag("user-context", """
                [System note: User profile and preferences]

                ### User Profile (USER.md)
                """ + user));
        }

        return sb.toString();
    }

    /**
     * 获取 MemoryManager
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 设置记忆内容（用于测试或手动配置）
     */
    public void setMemoryContent(String content) {
        this.memoryContent = content;
    }

    /**
     * 设置用户内容（用于测试或手动配置）
     */
    public void setUserContent(String content) {
        this.userContent = content;
    }
}