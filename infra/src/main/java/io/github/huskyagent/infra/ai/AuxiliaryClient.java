package io.github.huskyagent.infra.ai;

import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.llm.api.LlmContentPart;
import io.github.huskyagent.infra.llm.api.LlmMessage;
import io.github.huskyagent.infra.llm.api.LlmRequest;
import io.github.huskyagent.infra.llm.api.LlmResult;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.llm.transport.OpenAiChatCompletionsTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight LLM helper for non-agent tasks (summary, title, web extract, vision).
 * Uses {@link LlmTransport} with OpenAI Chat Completions wire format only —
 * independent of the main agent's protocol (e.g. main may be Anthropic).
 */
@Slf4j
public class AuxiliaryClient {

    private final LlmTransport transport;
    private final String modelName;
    private final AgentConfig.AuxiliaryConfig config;

    public AuxiliaryClient(LlmTransport transport, AgentConfig.AuxiliaryConfig config) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.config = Objects.requireNonNull(config, "config");
        this.modelName = config.getModel();
    }

    /**
     * Build from auxiliary config, falling back to shared OpenAI-compatible main endpoint fields.
     */
    public static AuxiliaryClient create(AgentConfig.AuxiliaryConfig config,
                                         String sharedBaseUrl,
                                         String sharedApiKey,
                                         String sharedCompletionsPath) {
        Objects.requireNonNull(config, "config");
        boolean independent = notBlank(config.getBaseUrl()) && notBlank(config.getApiKey());
        String baseUrl = independent ? config.getBaseUrl().trim() : requireBase(sharedBaseUrl);
        String apiKey = independent
                ? config.getApiKey().trim()
                : (sharedApiKey != null ? sharedApiKey : "");
        String path = notBlank(config.getCompletionsPath())
                ? config.getCompletionsPath().trim()
                : (notBlank(sharedCompletionsPath) ? sharedCompletionsPath.trim() : "/v1/chat/completions");

        if (independent) {
            log.info("Auxiliary model uses independent endpoint: {}", baseUrl);
        } else {
            log.info("Auxiliary model shares main OpenAI-compatible endpoint, model={}", config.getModel());
        }

        LlmTransport transport = new OpenAiChatCompletionsTransport(
                baseUrl,
                apiKey,
                path,
                Duration.ofMinutes(3),
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build());
        return new AuxiliaryClient(transport, config);
    }

    public String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String prompt = """
            Please summarize the following content concisely, preserving:
            1. Key decisions and their reasons
            2. Important facts discovered
            3. Current task progress
            4. Pending items (if any)

            Content to summarize:
            ---
            %s
            ---

            Provide a concise summary in bullet points:
            """.formatted(content);

        try {
            return completeText(prompt, config.getMaxTokens());
        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage());
            return "Summary generation failed: " + e.getMessage();
        }
    }

    /**
     * Raw single-turn completion for callers that supply a full prompt (e.g. context compression).
     */
    public String completeText(String userPrompt) {
        return completeText(userPrompt, config.getMaxTokens());
    }

    public String completeText(String userPrompt, Integer maxTokens) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "";
        }
        LlmResult result = transport.complete(LlmRequest.builder()
                .model(modelName)
                .messages(List.of(LlmMessage.user(userPrompt)))
                .temperature(config.getTemperature())
                .maxTokens(maxTokens != null ? maxTokens : config.getMaxTokens())
                .stream(false)
                .build());
        return result.text() != null ? result.text() : "";
    }

    public String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "New Conversation";
        }

        String truncatedMessage = firstMessage.length() > 500
                ? firstMessage.substring(0, 500) + "..."
                : firstMessage;

        String prompt = """
            Generate a short, descriptive title (max 50 chars) for this conversation.
            Return ONLY the title, nothing else.

            First message:
            %s
            """.formatted(truncatedMessage);

        try {
            String title = completeText(prompt, config.getMaxTokens());
            if (!title.isBlank()) {
                title = title.trim()
                        .replaceAll("^[\"']|[\"']$", "")
                        .replaceAll("[\\r\\n]", " ")
                        .trim();
                if (title.length() > 50) {
                    title = title.substring(0, 47) + "...";
                }
            }
            return !title.isBlank() ? title : "New Conversation";
        } catch (Exception e) {
            log.error("Failed to generate title: {}", e.getMessage());
            return "New Conversation";
        }
    }

    public String analyzeImage(byte[] imageData, String mimeType) {
        return analyzeImage(imageData, mimeType, "Please analyze the attached image.");
    }

    public String analyzeImage(byte[] imageData, String mimeType, String question) {
        if (imageData == null || imageData.length == 0) {
            return "Image analysis failed: image data is empty";
        }
        if (mimeType == null || mimeType.isBlank()) {
            return "Image analysis failed: image MIME type is required";
        }
        if (question == null || question.isBlank()) {
            return "Image analysis failed: question is required";
        }

        String prompt = """
            Analyze the attached image carefully.
            First describe the visible content and extract any relevant visible text.
            Then answer this question directly: %s
            """.formatted(question.trim());

        try {
            String b64 = Base64.getEncoder().encodeToString(imageData);
            LlmMessage message = LlmMessage.userMultimodal(
                    prompt,
                    List.of(LlmContentPart.imageBase64(mimeType.trim(), b64)));
            LlmResult result = transport.complete(LlmRequest.builder()
                    .model(modelName)
                    .messages(List.of(message))
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .stream(false)
                    .build());
            String text = result.text();
            return text != null && !text.isBlank() ? text : "Image analysis returned empty content";
        } catch (Exception e) {
            log.error("Failed to analyze image with model {}: {}", modelName, e.getMessage());
            return "Image analysis failed: " + e.getMessage();
        }
    }

    public String translate(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String prompt = """
            Translate the following text to %s.
            Return ONLY the translation, nothing else.

            Text:
            %s
            """.formatted(targetLang, text);

        try {
            return completeText(prompt, config.getMaxTokens());
        } catch (Exception e) {
            log.error("Failed to translate: {}", e.getMessage());
            return text;
        }
    }

    public String extractKeyInfo(String content, String focus) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String prompt = """
            Extract key information about "%s" from the following content.
            Be concise and specific.

            Content:
            ---
            %s
            ---

            Key information:
            """.formatted(focus, content);

        try {
            return completeText(prompt, config.getMaxTokens());
        } catch (Exception e) {
            log.error("Failed to extract key info: {}", e.getMessage());
            return "";
        }
    }

    public String summarizeForWeb(String content, String url, String title) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String truncatedContent = content.length() > 50000
                ? content.substring(0, 50000) + "\n...[content truncated for summarization]"
                : content;

        String prompt = """
            Extract and summarize the key information from this web page content.
            Preserve: main topic, key facts, important details, actionable information.
            Remove: navigation menus, ads, boilerplate, repetitive content.

            URL: %s
            Title: %s

            Content:
            ---
            %s
            ---

            Provide a concise but comprehensive summary:
            """.formatted(
                url != null ? url : "unknown",
                title != null ? title : "untitled",
                truncatedContent
        );

        try {
            int max = config.getWebSummaryMaxTokens() > 0
                    ? config.getWebSummaryMaxTokens()
                    : config.getMaxTokens();
            String text = completeText(prompt, max);
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.error("Failed to summarize web content: {}", e.getMessage());
            return null;
        }
    }

    private static String requireBase(String sharedBaseUrl) {
        if (!notBlank(sharedBaseUrl)) {
            throw new IllegalArgumentException(
                    "Auxiliary LLM has no base-url: set agent.auxiliary.base-url or spring.ai.openai.base-url / OPENAI_BASE_URL");
        }
        return sharedBaseUrl.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
