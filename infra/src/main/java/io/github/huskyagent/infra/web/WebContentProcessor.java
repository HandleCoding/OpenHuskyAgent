package io.github.huskyagent.infra.web;

import io.github.huskyagent.infra.ai.AuxiliaryClient;
import io.github.huskyagent.infra.config.WebConfig;
import io.github.huskyagent.infra.http.HttpClientFactory;
import io.github.huskyagent.infra.http.ProxyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Web 内容处理器
 * 负责 URL 抓取、HTML→文本转换、LLM 摘要
 * 支持 gzip/deflate 内容编码自动解压（Java HttpClient 不内置此功能，需手动处理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebContentProcessor {

    private final AuxiliaryClient auxiliaryClient;
    private final WebConfig config;
    private final HttpClientFactory httpClientFactory;

    /**
     * 抓取 URL 并返回处理后的内容
     */
    public FetchResult fetchUrl(String url, boolean summarize) {
        try {
            URI fetchUri = URI.create(url);
            HttpClient client = httpClientFactory.createFollowRedirects(
                fetchUri,
                Duration.ofSeconds(config.getRequestTimeoutSeconds()),
                fetchProxyOptions()
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .header("User-Agent", "HuskyAgent/1.0")
                .GET()
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .build();

            // 使用 InputStream 接收响应，手动处理 gzip/deflate 解压
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                // 带尾斜杠 404 时，自动尝试去掉尾斜杠重试（很多 SPA/SSG 站点路径不带尾斜杠）
                if (response.statusCode() == 404 && url.endsWith("/")) {
                    String urlWithoutSlash = url.substring(0, url.length() - 1);
                    log.info("URL {} returned 404 with trailing slash, retrying without: {}", url, urlWithoutSlash);
                    return fetchUrl(urlWithoutSlash, summarize);
                }

                // 尝试读取错误响应体，可能包含有用的重定向信息或页面建议
                String errorBody = "";
                try {
                    String encoding = response.headers().firstValue("Content-Encoding").orElse("");
                    InputStream errStream = response.body();
                    switch (encoding) {
                        case "gzip" -> errStream = new GZIPInputStream(errStream);
                        case "deflate" -> errStream = new InflaterInputStream(errStream);
                        default -> {}
                    }
                    String rawBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    errStream.close();
                    if (rawBody != null && !rawBody.isBlank()) {
                        String text = Jsoup.parse(rawBody).text();
                        if (text.length() > 500) text = text.substring(0, 500) + "...";
                        errorBody = text;
                    }
                } catch (Exception ignored) {}
                String msg = "HTTP error: " + response.statusCode();
                if (!errorBody.isBlank()) msg += " — " + errorBody;
                return FetchResult.error(url, msg);
            }

            // 根据 Content-Encoding 解压
            String encoding = response.headers().firstValue("Content-Encoding").orElse("");
            InputStream bodyStream = response.body();

            switch (encoding) {
                case "gzip" -> bodyStream = new GZIPInputStream(bodyStream);
                case "deflate" -> bodyStream = new InflaterInputStream(bodyStream);
                default -> { /* 不需要解压 */ }
            }

            String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
            bodyStream.close();

            if (body.length() > config.getMaxFetchSizeBytes()) {
                return FetchResult.error(url, "Content too large (" + body.length() + " chars), max is " + config.getMaxFetchSizeBytes());
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("text/html");
            boolean isHtml = contentType.contains("html");

            String title;
            String content;

            if (isHtml) {
                var doc = Jsoup.parse(body, url);
                title = doc.title();
                content = Jsoup.clean(doc.html(), Safelist.relaxed());
                content = Jsoup.parse(content).text();
            } else {
                title = extractTitleFromUrl(url);
                content = body;
            }

            int originalSize = content.length();
            boolean wasSummarized = false;

            if (summarize && content.length() > config.getSummarizeThresholdChars()) {
                String summary = auxiliaryClient.summarizeForWeb(content, url, title);
                if (summary != null && !summary.isBlank()) {
                    content = summary;
                    wasSummarized = true;
                } else {
                    content = truncateContent(content, config.getMaxOutputChars(),
                        "[内容截断 - LLM摘要失败]");
                }
            }

            if (content.length() > config.getMaxOutputChars() * 2) {
                content = truncateContent(content, config.getMaxOutputChars(),
                    "[内容截断]");
            }

            return new FetchResult(url, title, content, originalSize, wasSummarized, null);

        } catch (Exception e) {
            log.error("Failed to fetch URL {}: {}", url, e.getMessage());
            return FetchResult.error(url, "Fetch failed: " + e.getMessage());
        }
    }

    private String truncateContent(String content, int maxChars, String suffix) {
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n" + suffix;
    }

    private String extractTitleFromUrl(String url) {
        try {
            String path = new URI(url).getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                return path.substring(path.lastIndexOf('/') + 1);
            }
            return new URI(url).getHost();
        } catch (Exception e) {
            return "untitled";
        }
    }

    private ProxyResolver.ServiceProxyOptions fetchProxyOptions() {
        WebConfig.ProxySettings proxy = config.getProxy();
        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        if (proxy != null) {
            options.setEnabled(proxy.getEnabled());
            options.setUrl(proxy.getUrl());
            options.setNoProxy(proxy.getNoProxy());
        }
        return options;
    }

    /**
     * 抓取结果
     */
    public record FetchResult(
        String url,
        String title,
        String content,
        int originalSize,
        boolean wasSummarized,
        String error
    ) {
        public static FetchResult error(String url, String error) {
            return new FetchResult(url, null, null, 0, false, error);
        }
    }
}