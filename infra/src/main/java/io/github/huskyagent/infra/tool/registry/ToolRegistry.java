package io.github.huskyagent.infra.tool.registry;

import io.github.huskyagent.infra.tool.Toolset;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 *
 * 特性：
 * 1. ToolProvider 自动发现 - 注入所有 ToolProvider bean，@PostConstruct 统一注册
 * 2. 工具分组 - 按 Toolset 组织
 * 3. 工具路由 - 按 name 查找 handler
 * 4. 线程安全 - ConcurrentHashMap
 */
@Slf4j
@Component
public class ToolRegistry {

    private final List<ToolProvider> toolProviders;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<Toolset, Set<String>> toolsetTools = new ConcurrentHashMap<>();
    private final Map<String, String> toolsetAliases = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> providerTools = new ConcurrentHashMap<>();

    public ToolRegistry(List<ToolProvider> toolProviders) {
        this.toolProviders = toolProviders;
    }

    @PostConstruct
    public void init() {
        for (ToolProvider provider : toolProviders) {
            if (provider instanceof DynamicToolProvider dynamicProvider) {
                replaceProviderTools(dynamicProvider.providerKey(), dynamicProvider.getTools());
                dynamicProvider.setToolsChangedListener(
                        () -> replaceProviderTools(dynamicProvider.providerKey(), dynamicProvider.getTools()));
                continue;
            }
            for (ToolDefinition def : provider.getTools()) {
                register(def);
            }
        }
        log.info("ToolRegistry initialized: {} tools from {} providers",
            tools.size(), toolProviders.size());
    }

    /**
     * 注册工具
     */
    public synchronized void register(ToolDefinition tool) {
        String name = tool.name();

        // 检查是否已存在
        ToolDefinition existing = tools.get(name);
        if (existing != null && existing.toolset() != tool.toolset()) {
            log.warn("Tool '{}' from toolset '{}' would shadow existing tool from toolset '{}'. Rejecting.",
                name, tool.toolset(), existing.toolset());
            return;
        }

        tools.put(name, tool);
        toolsetTools.computeIfAbsent(tool.toolset(), k -> ConcurrentHashMap.newKeySet()).add(name);

        log.debug("Registered tool '{}' in toolset '{}'", name, tool.toolset());
    }

    /**
     * 注销工具
     */
    public synchronized void deregister(String name) {
        ToolDefinition removed = tools.remove(name);
        if (removed != null) {
            toolsetTools.getOrDefault(removed.toolset(), Set.of()).remove(name);
            providerTools.values().forEach(names -> names.remove(name));
            log.debug("Deregistered tool '{}'", name);
        }
    }

    public synchronized void replaceProviderTools(String providerKey, Collection<ToolDefinition> definitions) {
        Set<String> previous = providerTools.getOrDefault(providerKey, Set.of());
        for (String toolName : previous) {
            ToolDefinition removed = tools.remove(toolName);
            if (removed != null) {
                toolsetTools.getOrDefault(removed.toolset(), Set.of()).remove(toolName);
            }
        }

        Set<String> current = ConcurrentHashMap.newKeySet();
        for (ToolDefinition definition : definitions) {
            register(definition);
            current.add(definition.name());
        }
        providerTools.put(providerKey, current);
        log.info("Replaced {} tools for provider '{}'", current.size(), providerKey);
    }

    /**
     * 获取工具定义
     */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有工具
     */
    public List<ToolDefinition> getAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 获取所有启用的工具
     */
    public List<ToolDefinition> getAllEnabled() {
        return tools.values().stream()
            .filter(ToolDefinition::enabled)
            .collect(Collectors.toList());
    }

    /**
     * 获取指定 Toolset 的工具
     */
    public List<ToolDefinition> getByToolset(Toolset toolset) {
        Set<String> names = toolsetTools.getOrDefault(toolset, Set.of());
        return names.stream()
            .map(tools::get)
            .filter(Objects::nonNull)
            .filter(ToolDefinition::enabled)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有 Toolset 名称
     */
    public Set<Toolset> getRegisteredToolsets() {
        return new HashSet<>(toolsetTools.keySet());
    }

    /**
     * 注册 Toolset 别名
     */
    public void registerToolsetAlias(String alias, Toolset toolset) {
        toolsetAliases.put(alias, toolset.getName());
        log.debug("Registered alias '{}' for toolset '{}'", alias, toolset);
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取工具数量
     */
    public int size() {
        return tools.size();
    }

    /**
     * 获取工具统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTools", tools.size());
        stats.put("enabledTools", getAllEnabled().size());
        stats.put("toolsets", toolsetTools.entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().getName(),
                e -> e.getValue().size()
            )));
        return stats;
    }
}