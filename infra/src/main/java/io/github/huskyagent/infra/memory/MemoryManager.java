package io.github.huskyagent.infra.memory;

import io.github.huskyagent.infra.session.SessionScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 记忆管理器
 *
 * 协调多个 MemoryProvider，提供统一的记忆访问接口
 *
 * 设计原则：
 * 1. 支持 builtin（必选）、session（必选）、vector（可选）三种 Provider
 * 2. 聚合所有 Provider 的 prefetch 结果
 * 3. 路由工具调用到正确的 Provider
 * 4. 提供 Frozen Snapshot 系统提示注入
 */
@Slf4j
@Component
public class MemoryManager {

    private final List<MemoryProvider> providers = new ArrayList<>();
    private final MemoryRuntimeStrategyResolver strategyResolver;

    public MemoryManager(MemoryRuntimeStrategyResolver strategyResolver) {
        this.strategyResolver = strategyResolver;
    }

    /**
     * 注册 Provider
     */
    public void registerProvider(MemoryProvider provider) {
        providers.add(provider);
        log.info("Registered memory provider: {}", provider.getName());
    }

    /**
     * 初始化所有 Provider
     */
    public void initialize(MemoryContext context) {
        for (MemoryProvider provider : providers) {
            try {
                provider.initialize(context);
                log.info("Initialized memory provider: {}", provider.getName());
            } catch (Exception e) {
                log.error("Failed to initialize provider: {}", provider.getName(), e);
            }
        }
    }

    /**
     * 构建系统提示（聚合所有 Provider）
     *
     * 使用 Frozen Snapshot 模式，每个 Provider 返回冻结的内容
     */
    public String buildSystemPrompt(SessionScope scope) {
        SessionScope requiredScope = requireScope(scope);
        return strategy(requiredScope).loadForPrompt(new MemoryLoadRequest(
                requiredScope,
                providersForCurrentScope(requiredScope))).prompt();
    }

    /**
     * 聚合所有 Provider 的 prefetch 结果
     *
     * 并行调用各 Provider，按评分排序返回 TopK
     */
    public MemoryResult prefetchAll(SessionScope scope, String query, MemorySearchOptions options) {
        SessionScope requiredScope = requireScope(scope);
        return strategy(requiredScope).search(new MemorySearchRequest(
                requiredScope,
                providersForCurrentScope(requiredScope),
                query,
                options,
                "all",
                MemorySearchTrigger.PREFETCH));
    }

    public MemoryResult searchFromTool(SessionScope scope, String providerId, String query, MemorySearchOptions options, String requestedScope) {
        SessionScope requiredScope = requireScope(scope);
        return strategy(requiredScope).search(new MemorySearchRequest(
                requiredScope,
                providersForTool(requiredScope, providerId),
                query,
                options,
                requestedScope,
                MemorySearchTrigger.TOOL));
    }

    /**
     * 同步对话轮次到所有 Provider
     */
    public void syncAll(SessionScope scope, String user, String assistant) {
        SessionScope requiredScope = requireScope(scope);
        strategy(requiredScope).afterTurn(new MemoryTurnRequest(
                requiredScope,
                providersForCurrentScope(requiredScope),
                user,
                assistant));
    }

    public MemoryWriteResult writeFromTool(SessionScope scope, String toolName, Map<String, Object> args) {
        SessionScope requiredScope = requireScope(scope);
        return strategy(requiredScope).write(new MemoryWriteRequest(
                requiredScope,
                providersForCurrentScope(requiredScope),
                toolName,
                args));
    }

    public void validateProviderIds(Set<String> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return;
        }
        Set<String> registered = providers.stream()
                .map(this::providerId)
                .collect(java.util.stream.Collectors.toSet());
        for (String providerId : providerIds) {
            if (!registered.contains(providerId)) {
                throw new IllegalArgumentException("Unknown memory provider: " + providerId);
            }
        }
    }

    public boolean isProviderEnabled(SessionScope scope, String providerId) {
        SessionScope requiredScope = requireScope(scope);
        return enabledProviderIds(requiredScope).isEmpty() || enabledProviderIds(requiredScope).contains(providerId);
    }

    private MemoryRuntimeStrategy strategy(SessionScope scope) {
        return strategyResolver.resolve(scope.getMemoryStrategyId());
    }

    private List<MemoryProvider> providersForCurrentScope(SessionScope scope) {
        Set<String> enabledProviderIds = enabledProviderIds(scope);
        if (enabledProviderIds.isEmpty()) {
            return providers;
        }
        return providers.stream()
                .filter(provider -> enabledProviderIds.contains(providerId(provider)))
                .toList();
    }

    private List<MemoryProvider> providersForTool(SessionScope scope, String providerId) {
        if (!isProviderEnabled(scope, providerId)) {
            return List.of();
        }
        return providers.stream()
                .filter(provider -> providerId.equals(providerId(provider)))
                .toList();
    }

    private Set<String> enabledProviderIds(SessionScope scope) {
        return scope.getMemoryProviderIds() != null
                ? scope.getMemoryProviderIds()
                : Set.of();
    }

    private SessionScope requireScope(SessionScope scope) {
        return Objects.requireNonNull(scope, "SessionScope is required for memory operations");
    }

    private String providerId(MemoryProvider provider) {
        return provider.getName();
    }

    /**
     * 获取所有已注册的 Provider
     */
    public List<MemoryProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /**
     * 检查是否有可用的 Provider
     */
    public boolean hasAvailableProvider() {
        return providers.stream().anyMatch(MemoryProvider::isAvailable);
    }
}