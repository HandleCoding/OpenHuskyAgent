package io.github.huskyagent.domain.hook;

import java.util.Set;

/**
 * Hook 基础接口 — 所有 Hook 必须实现此接口。
 *
 * <p>Hook 通过 Spring {@code @Component} 自动注册到 {@link HookRegistry}，
 * 与 {@code ToolProvider} 模式一致。</p>
 */
public interface AgentHook {

    /** Hook 唯一标识（用于配置开关和日志） */
    String name();

    /** 关注的事件类型 */
    Set<HookEvent> supportedEvents();

    /** 执行优先级（数字越小越先执行，默认 100） */
    default int order() { return 100; }
}
