package io.github.huskyagent.infra.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置 — 对应 mcp-servers.json 中单个 server 条目
 *
 * <p>兼容 Claude Desktop JSON 格式（无 enabled 字段时默认 true），
 * 扩展支持 enabled（按 server 开关）和 timeout（单次工具调用超时）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(

    /** stdio 传输：可执行命令（如 npx） */
    String command,

    /** stdio 传输：命令参数 */
    @JsonProperty("args") List<String> args,

    /** stdio 传输：环境变量 */
    Map<String, String> env,

    /** HTTP 传输：服务器 URL */
    String url,

    /**
     * HTTP 传输类型：auto（默认）/ sse / streamable-http
     *
     * <ul>
     *   <li>auto — 优先尝试 Streamable-HTTP，降级 SSE（标准 MCP server）</li>
     *   <li>sse — 强制使用 SSE transport（如 mcphub 等 API 网关，GET url 返回 event: endpoint）</li>
     *   <li>streamable-http — 强制使用 Streamable-HTTP transport</li>
     * </ul>
     */
    String transport,

    /** HTTP 传输：自定义请求头 */
    Map<String, String> headers,

    /** 是否启用；null 时默认 true（兼容 Claude Desktop JSON） */
    @JsonProperty("enabled") Boolean enabled,

    /** 单次工具调用超时（秒）；0 或缺失时使用默认 120s */
    int timeout

) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public int getTimeout() {
        return timeout > 0 ? timeout : 120;
    }

    /** 是否强制 SSE transport */
    public boolean isSseTransport() {
        return "sse".equalsIgnoreCase(transport);
    }

    /** 是否强制 Streamable-HTTP transport */
    public boolean isStreamableHttpTransport() {
        return "streamable-http".equalsIgnoreCase(transport);
    }
}
