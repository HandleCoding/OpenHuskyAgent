package io.github.huskyagent.infra.ai;

import io.github.huskyagent.infra.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Slf4j
public class AuxiliaryClient {

    private final ChatClient chatClient;
    private final String modelName;
    private final AgentConfig.AuxiliaryConfig config;

    public AuxiliaryClient(ChatModel mainChatModel, AgentConfig.AuxiliaryConfig config) {
        this.config = config;
        this.modelName = config.getModel();

        ChatModel effectiveChatModel;
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            log.info("Auxiliary model uses independent endpoint: {}", config.getBaseUrl());
            effectiveChatModel = buildIndependentChatModel(config);
        } else {
            log.info("Auxiliary model shares main endpoint, model={}", modelName);
            effectiveChatModel = mainChatModel;
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();

        this.chatClient = ChatClient.builder(effectiveChatModel)
                .defaultOptions(options)
                .build();
    }

    private static OpenAiChatModel buildIndependentChatModel(AgentConfig.AuxiliaryConfig config) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(30));
        requestFactory.setReadTimeout(Duration.ofMinutes(3));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .completionsPath(config.getCompletionsPath() != null && !config.getCompletionsPath().isBlank()
                        ? config.getCompletionsPath()
                        : "/v1/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.getModel())
                        .temperature(config.getTemperature())
                        .build())
                .build();
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
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage());
            return "Summary generation failed: " + e.getMessage();
        }
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
            String title = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (title != null) {
                title = title.trim()
                        .replaceAll("^[\"']|[\"']$", "")
                        .replaceAll("[\\r\\n]", " ")
                        .trim();

                if (title.length() > 50) {
                    title = title.substring(0, 47) + "...";
                }
            }

            return title != null ? title : "New Conversation";
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
            Media media = new Media(
                    MimeTypeUtils.parseMimeType(mimeType),
                    imageResource(imageData, mimeType)
            );
            UserMessage message = UserMessage.builder()
                    .text(prompt)
                    .media(List.of(media))
                    .build();

            return chatClient.prompt()
                    .messages(message)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Failed to analyze image with model {}: {}", modelName, e.getMessage());
            return "Image analysis failed: " + e.getMessage();
        }
    }

    private ByteArrayResource imageResource(byte[] imageData, String mimeType) {
        return new ByteArrayResource(imageData) {
            @Override
            public String getFilename() {
                return "image." + imageExtension(mimeType);
            }
        };
    }

    private String imageExtension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
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
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
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
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
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
            return chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().maxTokens(config.getWebSummaryMaxTokens()).build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Failed to summarize web content: {}", e.getMessage());
            return null;
        }
    }
}