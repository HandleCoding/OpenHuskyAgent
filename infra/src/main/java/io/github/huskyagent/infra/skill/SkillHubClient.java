package io.github.huskyagent.infra.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SkillHub API 客户端 — 搜索社区 skill + 获取 skill 详情。
 *
 * API:
 * - POST /skills/search — 搜索 skill（返回 slug, name, description, category）
 * - GET  /skills/{slug} — 获取 skill 详情（含 skill_md_raw, repo_url, skill_path）
 *
 * SkillHub API 需要认证（Bearer token）。
 */
@Slf4j
@Component
public class SkillHubClient {

    private final SkillHubConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SkillHubClient(SkillHubConfig config) {
        this.config = config;
    }

    /** 搜索 SkillHub 社区 skill */
    public SkillHubSearchResponse searchSkills(String query, int limit) {
        if (!config.isEnabled()) {
            return SkillHubSearchResponse.error("SkillHub is disabled. Set skillhub.enabled=true in application.yml.");
        }

        if (config.getApiKey().isBlank()) {
            return SkillHubSearchResponse.error("SkillHub API key is not configured. "
                    + "Set skillhub.api-key in application.yml or SKILLHUB_API_KEY env var. "
                    + "Get your key at https://www.skillhub.club");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "limit", limit,
                    "method", "hybrid"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/skills/search"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return SkillHubSearchResponse.error("SkillHub API authentication failed (HTTP 401). "
                        + "Check your skillhub.api-key configuration. Get your key at https://www.skillhub.club");
            }

            if (response.statusCode() != 200) {
                log.error("SkillHub search API returned {}: {}", response.statusCode(), truncate(response.body(), 500));
                return SkillHubSearchResponse.error("SkillHub search API error (HTTP " + response.statusCode() + "). "
                        + "The service may be temporarily unavailable.");
            }

            List<SkillHubSearchResult> results = parseSearchResults(response.body());
            return SkillHubSearchResponse.success(results);
        } catch (Exception e) {
            log.error("SkillHub search failed: {}", e.getMessage());
            return SkillHubSearchResponse.error("SkillHub search request failed: " + e.getMessage());
        }
    }

    /** 获取 skill 详情 — GET /skills/{slug}，返回 skill_md_raw + repo_url */
    public SkillHubSkillDetail getSkillDetail(String slug) {
        if (!config.isEnabled()) return null;

        if (config.getApiKey().isBlank()) {
            log.warn("SkillHub API key not configured — cannot fetch skill detail");
            return null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/skills/" + slug))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .GET()
                    .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                log.warn("Skill not found on SkillHub: {}", slug);
                return null;
            }

            if (response.statusCode() != 200) {
                log.error("SkillHub detail API returned {}: {}", response.statusCode(), truncate(response.body(), 500));
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode skill = root.has("skill") ? root.get("skill") : root;

            String skillMdRaw = skill.has("skill_md_raw") ? skill.get("skill_md_raw").asText() : null;
            String repoUrl = skill.has("repo_url") ? skill.get("repo_url").asText() : null;
            String skillPath = skill.has("skill_path") ? skill.get("skill_path").asText() : null;

            if (skillMdRaw == null || skillMdRaw.isBlank()) {
                log.warn("SkillHub detail returned no skill_md_raw for: {}", slug);
                return null;
            }

            return new SkillHubSkillDetail(slug, skillMdRaw, repoUrl, skillPath);
        } catch (Exception e) {
            log.error("SkillHub detail request failed for {}: {}", slug, e.getMessage());
            return null;
        }
    }

    private List<SkillHubSearchResult> parseSearchResults(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.has("results") ? root.get("results") : root;

            if (!results.isArray()) return List.of();

            List<SkillHubSearchResult> list = new ArrayList<>();
            for (JsonNode item : results) {
                String name = getText(item, "name", "title");
                String slug = getText(item, "slug", "id");
                String description = getText(item, "description", "description_zh");
                String category = getText(item, "category");

                if (name == null || slug == null) continue;
                list.add(new SkillHubSearchResult(name, slug, description, category));
            }
            return list;
        } catch (Exception e) {
            log.error("Failed to parse SkillHub search results: {}", e.getMessage());
            return List.of();
        }
    }

    private String getText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.get(key).isNull()) {
                String val = node.get(key).asText();
                if (!val.isBlank()) return val;
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── 响应模型 ──────────────────────────────────────────────────────

    public record SkillHubSearchResult(
            String name,
            String slug,
            String description,
            String category
    ) {}

    public record SkillHubSkillDetail(
            String slug,
            String skillMdRaw,
            String repoUrl,
            String skillPath
    ) {}

    public record SkillHubSearchResponse(
            boolean success,
            List<SkillHubSearchResult> results,
            String error
    ) {
        public static SkillHubSearchResponse success(List<SkillHubSearchResult> results) {
            return new SkillHubSearchResponse(true, results, null);
        }
        public static SkillHubSearchResponse error(String error) {
            return new SkillHubSearchResponse(false, List.of(), error);
        }
    }
}