package io.github.huskyagent.infra.runtime.watch;

/**
 * 由上层实现的缓存失效入口，避免 infra 直接依赖 application/domain 实现类。
 */
public interface RuntimeReloadInvalidation {

    void clearPromptCache();

    void clearGraphCache();
}
