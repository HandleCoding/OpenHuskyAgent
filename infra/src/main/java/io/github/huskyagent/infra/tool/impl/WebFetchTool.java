package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.web.UrlSafety;
import io.github.huskyagent.infra.web.WebContentProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 抓取工具
 * 抓取 URL 内容并转换为文本，支持 LLM 摘要化大内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebFetchTool implements ToolProvider {

    private final WebContentProcessor contentProcessor;
    private final WebConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ToolDefinition> getTools() {
        if (!config.isBackendAvailable()) {
            return List.of();
        }
        log.info("Registered web_fetch tool");
        return List.of(buildDefinition());
    }

    private ToolDefinition buildDefinition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        ObjectNode urlNode = props.putObject("url");
        urlNode.put("type", "string");
        urlNode.put("description", "URL to fetch content from");

        ObjectNode summarizeNode = props.putObject("summarize");
        summarizeNode.put("type", "boolean");
        summarizeNode.put("description", "Auto-summarize large content (>5000 chars) via LLM (default: true)");
        summarizeNode.put("default", true);

        ObjectNode useJinaNode = props.putObject("useJina");
        useJinaNode.put("type", "boolean");
        useJinaNode.put("description", "Set to true ONLY when the user explicitly requests Jina Reader extraction. Do not use Jina by default. Jina converts pages to LLM-friendly Markdown via https://r.jina.ai/. Default: false.");
        useJinaNode.put("default", false);

        ArrayNode required = schema.putArray("required");
        required.add("url");

        return ToolDefinition.of(
                "web_fetch",
                "Fetch and extract content from a web page URL. Use this for text webpages, articles, and HTML pages. Do not use for direct image files such as .jpg/.jpeg/.png/.gif/.webp; use vision_analyze for image URLs. Returns page text as-is by default; use useJina=true only when the user explicitly asks for Jina Reader Markdown extraction. Large content (>5000 chars) is auto-summarized. Content over 2MB is refused.",
                Toolset.WEB,
                schema,
                this::handle)
                .withEmoji("\uD83D\uDCD6")
                .withMaxResultSize(100_000);
    }

    public ToolResult handle(Map<String, Object> args) {
        String url = (String) args.get("url");
        boolean summarize = !Boolean.FALSE.equals(args.get("summarize"));
        boolean useJina = Boolean.TRUE.equals(args.get("useJina"));

        if (url == null || url.isBlank()) {
            return ToolResult.failure("url is required", false, "Provide a URL to fetch");
        }

        // 自动添加 https://
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        // 安全检查: 嵌入的秘密信息
        if (UrlSafety.containsSecret(url)) {
            return ToolResult.failure("Blocked: URL contains what appears to be an API key or token",
                false, "Remove embedded secrets from the URL");
        }

        // 安全检查: SSRF 防护
        if (!UrlSafety.isSafeUrl(url)) {
            return ToolResult.failure("Blocked: URL targets a private or internal network address",
                false, "Use a public URL instead");
        }

        // 域名黑名单检查
        String host = extractHost(url);
        for (String blocked : config.getBlockedDomains()) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return ToolResult.failure("Blocked by website policy: '" + host + "' matched rule '" + blocked + "'",
                    false, "This domain is on the blocklist");
            }
        }

        try {
            String fetchUrl = useJina ? jinaReaderUrl(url) : url;
            WebContentProcessor.FetchResult result = contentProcessor.fetchUrl(fetchUrl, summarize);

            if (result.error() != null) {
                return ToolResult.failure(result.error(), true, "Try a different URL or check connectivity");
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("url", url);
            response.put("fetchedUrl", result.url());
            response.put("usedJina", useJina);
            response.put("title", result.title());
            response.put("content", result.content());
            response.put("originalSize", result.originalSize());
            response.put("wasSummarized", result.wasSummarized());

            return ToolResult.success(response);

        } catch (Exception e) {
            log.error("web_fetch failed for {}: {}", url, e.getMessage());
            return ToolResult.failure("Fetch failed: " + e.getMessage(), true,
                "Check URL validity and connectivity");
        }
    }

    String jinaReaderUrl(String url) {
        return "https://r.jina.ai/" + url;
    }

    private String extractHost(String url) {
        try {
            return new URI(url).getHost().toLowerCase();
        } catch (URISyntaxException e) {
            return "";
        }
    }
}