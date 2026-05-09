package io.github.huskyagent.infra.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP 服务器配置加载器
 *
 * <p>读取 mcp-servers.json，过滤 enabled 服务器。
 * 文件不存在或格式错误时返回空 map，不崩溃。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConfigLoader {

    private final ObjectMapper objectMapper;
    private final Path configPath;

    public McpConfigLoader(ObjectMapper objectMapper,
                           @Value("${mcp.config-path:}") String configPathStr) {
        this.objectMapper = objectMapper;
        this.configPath = configPathStr != null && !configPathStr.isBlank()
                ? Path.of(configPathStr) : null;
    }

    /**
     * 加载仅 enabled 的服务器配置
     */
    public ConfigLoadResult loadEnabledServersResult() {
        ConfigLoadResult all = loadAllServersResult();
        if (!all.success()) {
            return all;
        }
        Map<String, McpServerConfig> enabled = new LinkedHashMap<>();
        all.servers().forEach((name, config) -> {
            if (config.isEnabled()) {
                enabled.put(name, config);
            } else {
                log.debug("MCP server '{}' disabled, skipping", name);
            }
        });
        return ConfigLoadResult.success(enabled);
    }

    public Map<String, McpServerConfig> loadEnabledServers() {
        return loadEnabledServersResult().servers();
    }

    /**
     * 加载全部服务器配置（含 disabled，用于状态展示）
     */
    public ConfigLoadResult loadAllServersResult() {
        if (configPath == null) {
            log.debug("No MCP config path configured, skipping MCP server discovery");
            return ConfigLoadResult.success(Map.of());
        }

        if (!Files.exists(configPath)) {
            log.warn("MCP config file not found: {}", configPath);
            return ConfigLoadResult.success(Map.of());
        }

        try {
            JsonNode root = objectMapper.readTree(configPath.toFile());
            JsonNode servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.warn("MCP config missing 'mcpServers' key or not an object");
                return ConfigLoadResult.failure("MCP config missing 'mcpServers' key or not an object");
            }

            Map<String, McpServerConfig> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String serverName = entry.getKey();
                try {
                    McpServerConfig config = objectMapper.treeToValue(entry.getValue(), McpServerConfig.class);
                    result.put(serverName, config);
                } catch (Exception e) {
                    log.warn("Failed to parse MCP server '{}': {}", serverName, e.getMessage());
                    return ConfigLoadResult.failure("Failed to parse MCP server '" + serverName + "': " + e.getMessage());
                }
            }

            log.info("Loaded {} MCP server configs from {}", result.size(), configPath);
            return ConfigLoadResult.success(result);

        } catch (IOException e) {
            log.error("Failed to read MCP config: {}", e.getMessage());
            return ConfigLoadResult.failure("Failed to read MCP config: " + e.getMessage());
        }
    }

    public Map<String, McpServerConfig> loadAllServers() {
        return loadAllServersResult().servers();
    }

    /**
     * 配置文件路径（用于状态展示）
     */
    public Path getConfigPath() {
        return configPath;
    }

    public record ConfigLoadResult(boolean success, Map<String, McpServerConfig> servers, String errorMessage) {
        public static ConfigLoadResult success(Map<String, McpServerConfig> servers) {
            return new ConfigLoadResult(true, Map.copyOf(servers), null);
        }

        public static ConfigLoadResult failure(String errorMessage) {
            return new ConfigLoadResult(false, Map.of(), errorMessage);
        }
    }
}
