package io.github.huskyagent.infra.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 服务器连接器 — 管理 McpSyncClient 生命周期
 *
 * <p>特性：
 * <ul>
 *   <li>重连：定时 ping 健康检查，断连后指数退避重连（最多 5 次）</li>
 *   <li>熔断：连续 3 次调用失败后熔断，30s 后半开探测恢复</li>
 *   <li>变更监听：注册 toolsChangeConsumer，工具变更时通知 McpToolProvider 刷新</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpServerConnector {

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY_MS = 2000;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 30_000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60_000;

    private final McpConnectionProvider connectionProvider;

    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<McpSchema.Tool>> discoveredTools = new ConcurrentHashMap<>();
    private final Map<String, ServerStatus> statuses = new ConcurrentHashMap<>();

    // 熔断状态
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> circuitOpenTimes = new ConcurrentHashMap<>();

    // 重连调度
    private ScheduledExecutorService scheduler;
    private final Set<String> reconnectingServers = ConcurrentHashMap.newKeySet();

    // 工具变更监听器
    private volatile Runnable toolsChangeListener;

    public McpServerConnector(McpConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @PostConstruct
    public void connectAll() {
        McpConnectionProvider.ServerLoadResult loadResult = connectionProvider.loadEnabledServers();
        if (!loadResult.success()) {
            log.warn("Failed to load MCP server config: {}", loadResult.errorMessage());
            return;
        }

        Map<String, McpServerConfig> servers = loadResult.servers();
        if (servers.isEmpty()) {
            log.info("No enabled MCP servers configured");
            return;
        }

        ensureScheduler();

        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            connectServer(entry.getKey(), entry.getValue());
        }

        long connected = statuses.values().stream().filter(s -> s == ServerStatus.CONNECTED).count();
        log.info("MCP connector: {} of {} servers connected successfully", connected, servers.size());

        // 启动健康检查
        if (!clients.isEmpty()) {
            scheduler.scheduleAtFixedRate(this::healthCheck,
                    HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 设置工具变更监听器。McpToolProvider 注册此回调以刷新工具列表。
     */
    public void setToolsChangeListener(Runnable listener) {
        this.toolsChangeListener = listener;
    }

    /**
     * 连接单个服务器。失败时 log warn 并跳过，不抛异常。
     */
    public McpSyncClient connectServer(String name, McpServerConfig config) {
        serverConfigs.put(name, config);
        try {
            McpSyncClient client = buildClient(name, config);
            client.initialize();

            var toolResult = client.listTools();
            List<McpSchema.Tool> tools = toolResult.tools();

            clients.put(name, client);
            discoveredTools.put(name, tools);
            statuses.put(name, ServerStatus.CONNECTED);
            failureCounts.remove(name);
            circuitOpenTimes.remove(name);

            log.info("MCP server '{}' connected: {} tools discovered ({})",
                    name, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            return client;

        } catch (Exception e) {
            statuses.put(name, ServerStatus.FAILED);
            log.warn("MCP server '{}' failed to connect: {}", name, e.getMessage());
            scheduleReconnect(name);
            return null;
        }
    }

    /**
     * 执行工具调用（带熔断保护）。
     * McpToolProvider 的 handler 应通过此方法调用，而非直接调用 client。
     */
    public McpSchema.CallToolResult callTool(String serverName, McpSchema.CallToolRequest request) {
        if (isCircuitOpen(serverName)) {
            throw new CircuitOpenException("MCP server '" + serverName + "' is circuit-broken");
        }

        McpSyncClient client = clients.get(serverName);
        if (client == null) {
            throw new ServerNotConnectedException("MCP server '" + serverName + "' is not connected");
        }

        try {
            McpSchema.CallToolResult result = client.callTool(request);
            onFailureReset(serverName);
            return result;
        } catch (Exception e) {
            onFailure(serverName);
            throw e;
        }
    }

    /**
     * 判断服务器是否处于熔断状态
     */
    public boolean isCircuitOpen(String serverName) {
        Integer failures = failureCounts.get(serverName);
        if (failures == null || failures < CIRCUIT_BREAKER_THRESHOLD) {
            return false;
        }
        Long openTime = circuitOpenTimes.get(serverName);
        if (openTime == null) {
            return false;
        }
        // 冷却期过后，半开：允许一次探测
        if (System.currentTimeMillis() - openTime > CIRCUIT_BREAKER_COOLDOWN_MS) {
            log.info("MCP server '{}' circuit breaker half-open, allowing probe", serverName);
            return false;
        }
        return true;
    }

    /**
     * 刷新指定服务器的工具列表。由 toolsChangeConsumer 触发。
     */
    public void refreshTools(String serverName) {
        McpSyncClient client = clients.get(serverName);
        if (client == null) return;

        try {
            var toolResult = client.listTools();
            List<McpSchema.Tool> newTools = toolResult.tools();
            List<McpSchema.Tool> oldTools = discoveredTools.put(serverName, newTools);

            Set<String> oldNames = oldTools != null
                    ? new HashSet<>(oldTools.stream().map(McpSchema.Tool::name).toList())
                    : Set.of();
            Set<String> newNames = new HashSet<>(newTools.stream().map(McpSchema.Tool::name).toList());

            if (!oldNames.equals(newNames)) {
                log.info("MCP server '{}' tools changed: added={}, removed={}",
                        serverName,
                        newNames.stream().filter(n -> !oldNames.contains(n)).toList(),
                        oldNames.stream().filter(n -> !newNames.contains(n)).toList());

                // 通知 McpToolProvider 刷新
                if (toolsChangeListener != null) {
                    toolsChangeListener.run();
                }
            }
        } catch (Exception e) {
            log.warn("MCP server '{}' failed to refresh tools: {}", serverName, e.getMessage());
        }
    }

    public synchronized ReconcileResult reconcileWithConfig() {
        McpConnectionProvider.ServerLoadResult loadResult = connectionProvider.loadEnabledServers();
        if (!loadResult.success()) {
            return ReconcileResult.failure(loadResult.errorMessage());
        }

        Map<String, McpServerConfig> enabledServers = loadResult.servers();
        Map<String, McpServerConfig> targetConfigs = new HashMap<>(serverConfigs);
        Map<String, List<McpSchema.Tool>> targetTools = new HashMap<>(discoveredTools);
        Map<String, ServerStatus> targetStatuses = new HashMap<>(statuses);
        Map<String, McpSyncClient> stagedClients = new HashMap<>();
        List<String> removedServers = new ArrayList<>();

        Set<String> configuredNames = new HashSet<>(enabledServers.keySet());
        Set<String> existingNames = new HashSet<>(serverConfigs.keySet());

        for (String existingName : existingNames) {
            if (!configuredNames.contains(existingName)) {
                removedServers.add(existingName);
                targetConfigs.remove(existingName);
                targetTools.remove(existingName);
                targetStatuses.remove(existingName);
            }
        }

        for (Map.Entry<String, McpServerConfig> entry : enabledServers.entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig newConfig = entry.getValue();
            McpServerConfig oldConfig = serverConfigs.get(serverName);
            if (oldConfig != null && Objects.equals(oldConfig, newConfig)) {
                continue;
            }

            try {
                ConnectionSnapshot snapshot = openConnection(serverName, newConfig);
                stagedClients.put(serverName, snapshot.client());
                targetConfigs.put(serverName, newConfig);
                targetTools.put(serverName, snapshot.tools());
                targetStatuses.put(serverName, ServerStatus.CONNECTED);
            } catch (Exception e) {
                stagedClients.values().forEach(client -> {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                });
                return ReconcileResult.failure("MCP server '" + serverName + "' failed to connect: " + e.getMessage());
            }
        }

        Map<String, McpSyncClient> previousClients = new HashMap<>(clients);
        for (String removedServer : removedServers) {
            disconnectServer(removedServer);
        }
        for (String serverName : stagedClients.keySet()) {
            disconnectServer(serverName, true);
        }

        clients.putAll(stagedClients);
        serverConfigs.clear();
        serverConfigs.putAll(targetConfigs);
        discoveredTools.clear();
        discoveredTools.putAll(targetTools);
        statuses.clear();
        statuses.putAll(targetStatuses);
        stagedClients.keySet().forEach(serverName -> {
            failureCounts.remove(serverName);
            circuitOpenTimes.remove(serverName);
            reconnectingServers.remove(serverName);
        });

        previousClients.forEach((serverName, client) -> {
            if (stagedClients.containsKey(serverName) || removedServers.contains(serverName)) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        });

        if (toolsChangeListener != null) {
            toolsChangeListener.run();
        }
        return ReconcileResult.success(enabledServers.size(), removedServers.size());
    }

    public McpSyncClient getClient(String serverName) {
        return clients.get(serverName);
    }

    public List<McpSchema.Tool> getTools(String serverName) {
        return discoveredTools.getOrDefault(serverName, List.of());
    }

    public Set<String> getConnectedServerNames() {
        return Collections.unmodifiableSet(clients.keySet());
    }

    public ServerStatus getStatus(String serverName) {
        return statuses.getOrDefault(serverName, ServerStatus.NOT_CONFIGURED);
    }

    public Map<String, ServerStatus> getAllStatuses() {
        return Collections.unmodifiableMap(statuses);
    }

    // ── 健康检查与重连 ──────────────────────────────────────────────────

    private ConnectionSnapshot openConnection(String name, McpServerConfig config) {
        McpSyncClient client = buildClient(name, config);
        client.initialize();
        var toolResult = client.listTools();
        List<McpSchema.Tool> tools = toolResult.tools();
        log.info("MCP server '{}' staged connection: {} tools discovered ({})",
                name, tools.size(), tools.stream().map(McpSchema.Tool::name).toList());
        return new ConnectionSnapshot(client, List.copyOf(tools));
    }

    private void healthCheck() {
        for (String serverName : new HashSet<>(clients.keySet())) {
            try {
                McpSyncClient client = clients.get(serverName);
                if (client != null) {
                    client.ping();
                }
            } catch (Exception e) {
                log.warn("MCP server '{}' health check failed: {}", serverName, e.getMessage());
                handleDisconnection(serverName);
            }
        }
    }

    private void handleDisconnection(String serverName) {
        disconnectServer(serverName, true);
        scheduleReconnect(serverName);
    }

    private void disconnectServer(String serverName) {
        disconnectServer(serverName, false);
    }

    private void disconnectServer(String serverName, boolean keepConfig) {
        McpSyncClient old = clients.remove(serverName);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
        discoveredTools.remove(serverName);
        failureCounts.remove(serverName);
        circuitOpenTimes.remove(serverName);
        reconnectingServers.remove(serverName);
        statuses.put(serverName, ServerStatus.DISCONNECTED);
        if (!keepConfig) {
            serverConfigs.remove(serverName);
        }
    }

    private void reconnectServer(String serverName, McpServerConfig config) {
        disconnectServer(serverName);
        connectServer(serverName, config);
    }

    private void scheduleReconnect(String serverName) {
        if (!reconnectingServers.add(serverName)) {
            return; // 已在重连中
        }
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) return;

        ensureScheduler();
        scheduler.submit(() -> reconnectWithBackoff(serverName, config, 1));
    }

    private synchronized void ensureScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-health-check");
            t.setDaemon(true);
            return t;
        });
    }

    private void reconnectWithBackoff(String serverName, McpServerConfig config, int attempt) {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("MCP server '{}' reconnection exhausted after {} attempts", serverName, MAX_RECONNECT_ATTEMPTS);
            reconnectingServers.remove(serverName);
            statuses.put(serverName, ServerStatus.FAILED);
            return;
        }

        long delay = INITIAL_RECONNECT_DELAY_MS * (1L << (attempt - 1)); // 指数退避
        log.info("MCP server '{}' reconnect attempt {} in {}ms", serverName, attempt, delay);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reconnectingServers.remove(serverName);
            return;
        }

        try {
            McpSyncClient client = buildClient(serverName, config);
            client.initialize();

            var toolResult = client.listTools();
            List<McpSchema.Tool> tools = toolResult.tools();

            clients.put(serverName, client);
            discoveredTools.put(serverName, tools);
            statuses.put(serverName, ServerStatus.CONNECTED);
            failureCounts.remove(serverName);
            circuitOpenTimes.remove(serverName);
            reconnectingServers.remove(serverName);

            log.info("MCP server '{}' reconnected: {} tools discovered", serverName, tools.size());

            // 通知工具刷新
            if (toolsChangeListener != null) {
                toolsChangeListener.run();
            }
        } catch (Exception e) {
            log.warn("MCP server '{}' reconnect attempt {} failed: {}", serverName, attempt, e.getMessage());
            reconnectWithBackoff(serverName, config, attempt + 1);
        }
    }

    // ── 熔断 ──────────────────────────────────────────────────────────

    private void onFailure(String serverName) {
        int count = failureCounts.merge(serverName, 1, Integer::sum);
        if (count >= CIRCUIT_BREAKER_THRESHOLD) {
            circuitOpenTimes.put(serverName, System.currentTimeMillis());
            log.warn("MCP server '{}' circuit breaker opened after {} consecutive failures", serverName, count);
        }
    }

    private void onFailureReset(String serverName) {
        failureCounts.remove(serverName);
        circuitOpenTimes.remove(serverName);
    }

    // ── 客户端构建 ──────────────────────────────────────────────────

    private McpSyncClient buildClient(String name, McpServerConfig config) {
        Duration timeout = Duration.ofSeconds(config.getTimeout());

        if (config.isStdio()) {
            return buildStdioClient(config, timeout);
        } else if (config.isHttp()) {
            return buildHttpClient(name, config, timeout);
        } else {
            throw new IllegalArgumentException(
                    "MCP server '" + name + "' must have either 'command' (stdio) or 'url' (http)");
        }
    }

    private McpSyncClient buildStdioClient(McpServerConfig config, Duration timeout) {
        ServerParameters.Builder builder = ServerParameters.builder(config.command());
        if (config.args() != null) {
            builder.args(config.args());
        }
        if (config.env() != null && !config.env().isEmpty()) {
            builder.env(config.env());
        }
        ServerParameters params = builder.build();

        var transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .toolsChangeConsumer(tools -> handleToolsChange(config, tools))
                .build();
    }

    private McpSyncClient buildHttpClient(String name, McpServerConfig config, Duration timeout) {
        // SSE forced: API gateways (mcphub) where GET url returns "event: endpoint"
        if (config.isSseTransport()) {
            log.debug("MCP server '{}': using SSE transport (forced)", name);
            var transport = buildSseTransport(config);
            return McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .toolsChangeConsumer(tools -> handleToolsChange(config, tools))
                    .build();
        }

        // Streamable-HTTP forced or auto (auto falls back to SSE on NoClassDefFoundError)
        try {
            log.debug("MCP server '{}': using Streamable-HTTP transport", name);
            var transport = HttpClientStreamableHttpTransport.builder(config.url()).build();
            return McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .toolsChangeConsumer(tools -> handleToolsChange(config, tools))
                    .build();
        } catch (NoClassDefFoundError e) {
            if (config.isStreamableHttpTransport()) {
                throw new IllegalStateException("Streamable-HTTP transport not available for server: " + name, e);
            }
            log.debug("MCP server '{}': Streamable-HTTP not available, falling back to SSE", name);
            var transport = buildSseTransport(config);
            return McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .toolsChangeConsumer(tools -> handleToolsChange(config, tools))
                    .build();
        }
    }

    /**
     * Build SSE transport by splitting url into baseUrl + sseEndpoint path.
     * e.g. "http://host/path/to/mcp" → baseUrl="http://host", sseEndpoint="/path/to/mcp"
     */
    private HttpClientSseClientTransport buildSseTransport(McpServerConfig config) {
        String url = config.url();
        try {
            java.net.URI uri = java.net.URI.create(url);
            String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            return HttpClientSseClientTransport.builder(baseUrl)
                    .sseEndpoint(path)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse SSE url '{}', using as-is: {}", url, e.getMessage());
            return HttpClientSseClientTransport.builder(url).build();
        }
    }

    private void handleToolsChange(McpServerConfig config, List<McpSchema.Tool> newTools) {
        // 找到对应的服务器名
        for (Map.Entry<String, McpServerConfig> entry : serverConfigs.entrySet()) {
            if (entry.getValue() == config) {
                String serverName = entry.getKey();
                discoveredTools.put(serverName, newTools);
                log.info("MCP server '{}' tools changed notification: {} tools", serverName, newTools.size());

                if (toolsChangeListener != null) {
                    toolsChangeListener.run();
                }
                return;
            }
        }
    }

    // ── 关闭 ──────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdownAll() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        clients.forEach((name, client) -> {
            try {
                client.close();
                log.info("MCP server '{}' shut down", name);
            } catch (Exception e) {
                log.warn("Error shutting down MCP server '{}': {}", name, e.getMessage());
            }
        });
        clients.clear();
        discoveredTools.clear();
        statuses.clear();
        serverConfigs.clear();
        failureCounts.clear();
        circuitOpenTimes.clear();
    }

    // ── 异常类型 ──────────────────────────────────────────────────────

    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) { super(message); }
    }

    public static class ServerNotConnectedException extends RuntimeException {
        public ServerNotConnectedException(String message) { super(message); }
    }

    public record ReconcileResult(boolean success, String summary) {
        public static ReconcileResult success(int enabledServers, int removedServers) {
            return new ReconcileResult(true,
                    "reconciled " + enabledServers + " enabled server(s), removed " + removedServers + " server(s)");
        }

        public static ReconcileResult failure(String summary) {
            return new ReconcileResult(false, summary);
        }
    }

    private record ConnectionSnapshot(McpSyncClient client, List<McpSchema.Tool> tools) {
    }

    public enum ServerStatus {
        NOT_CONFIGURED, CONNECTED, DISCONNECTED, FAILED
    }
}
