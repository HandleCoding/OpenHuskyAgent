package io.github.huskyagent.infra.tool.registry;

import io.github.huskyagent.infra.tool.Toolset;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolRegistry {

    private final List<ToolProvider> toolProviders;
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<Toolset, Set<String>> toolsetTools = new ConcurrentHashMap<>();
    private final Map<String, String> toolsetAliases = new ConcurrentHashMap<>();
    /** Tracks which tool names belong to each provider so dynamic providers can replace their own tools safely. */
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

    public synchronized void register(ToolDefinition tool) {
        String name = tool.name();

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

    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    public List<ToolDefinition> getAll() {
        return new ArrayList<>(tools.values());
    }

    public List<ToolDefinition> getAllEnabled() {
        return tools.values().stream()
            .filter(ToolDefinition::enabled)
            .collect(Collectors.toList());
    }

    public List<ToolDefinition> getByToolset(Toolset toolset) {
        Set<String> names = toolsetTools.getOrDefault(toolset, Set.of());
        return names.stream()
            .map(tools::get)
            .filter(Objects::nonNull)
            .filter(ToolDefinition::enabled)
            .collect(Collectors.toList());
    }

    public Set<Toolset> getRegisteredToolsets() {
        return new HashSet<>(toolsetTools.keySet());
    }

    public void registerToolsetAlias(String alias, Toolset toolset) {
        toolsetAliases.put(alias, toolset.getName());
        log.debug("Registered alias '{}' for toolset '{}'", alias, toolset);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

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