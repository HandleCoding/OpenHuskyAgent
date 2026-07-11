package io.github.huskyagent.infra.llm;

import io.github.huskyagent.infra.config.AgentConfig;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.HuskyOpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@link ModelSelection} to a cached {@link ChatModel}.
 * Seeds a {@code main} provider from {@code spring.ai.openai.*} when not explicitly configured.
 */
@Slf4j
@Component
public class LlmClientRegistry {

    public static final String MAIN_PROVIDER = "main";
    public static final String TYPE_OPENAI_COMPATIBLE = "openai-compatible";

    private final LlmProperties llmProperties;
    private final AgentConfig agentConfig;
    private final ToolCallingManager toolCallingManager;
    private final ObjectProvider<RetryTemplate> retryTemplateProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    private final String springBaseUrl;
    private final String springApiKey;
    private final String springCompletionsPath;
    private final String springModel;
    private final Double springTemperature;

    private final ConcurrentHashMap<String, OpenAiApi> apiCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public LlmClientRegistry(
            LlmProperties llmProperties,
            AgentConfig agentConfig,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String springBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String springApiKey,
            @Value("${spring.ai.openai.completions-path:/v1/chat/completions}") String springCompletionsPath,
            @Value("${spring.ai.openai.chat.options.model:${OPENAI_MODEL:gpt-4o}}") String springModel,
            @Value("${spring.ai.openai.chat.options.temperature:0.7}") Double springTemperature) {
        this.llmProperties = llmProperties;
        this.agentConfig = agentConfig;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplateProvider = retryTemplateProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.springBaseUrl = springBaseUrl;
        this.springApiKey = springApiKey;
        this.springCompletionsPath = springCompletionsPath;
        this.springModel = springModel;
        this.springTemperature = springTemperature;
    }

    @PostConstruct
    void ensureDefaultProvider() {
        if (llmProperties.getProviders() == null) {
            llmProperties.setProviders(new java.util.LinkedHashMap<>());
        }
        Map<String, LlmProperties.Provider> providers = llmProperties.getProviders();
        String defaultId = defaultProviderId();
        LlmProperties.Provider existing = providers.get(defaultId);
        if (existing == null) {
            LlmProperties.Provider main = new LlmProperties.Provider();
            main.setType(TYPE_OPENAI_COMPATIBLE);
            main.setBaseUrl(springBaseUrl);
            main.setApiKey(springApiKey);
            main.setCompletionsPath(springCompletionsPath);
            main.setModel(springModel);
            main.setTemperature(springTemperature);
            providers.put(defaultId, main);
            log.info("Seeded LLM provider '{}' from spring.ai.openai.* (model={})", defaultId, springModel);
            return;
        }
        if (isBlank(existing.getBaseUrl())) {
            existing.setBaseUrl(springBaseUrl);
        }
        if (isBlank(existing.getApiKey())) {
            existing.setApiKey(springApiKey);
        }
        if (isBlank(existing.getCompletionsPath())) {
            existing.setCompletionsPath(springCompletionsPath);
        }
        if (isBlank(existing.getModel())) {
            existing.setModel(springModel);
        }
        if (existing.getTemperature() == null) {
            existing.setTemperature(springTemperature);
        }
        if (isBlank(existing.getType())) {
            existing.setType(TYPE_OPENAI_COMPATIBLE);
        }
    }

    public String defaultProviderId() {
        String id = llmProperties.getDefaultProvider();
        return isBlank(id) ? MAIN_PROVIDER : id.trim();
    }

    public String defaultModelName() {
        LlmProperties.Provider provider = providerOrNull(defaultProviderId());
        if (provider != null && !isBlank(provider.getModel())) {
            return provider.getModel().trim();
        }
        return springModel != null ? springModel.trim() : "";
    }

    /**
     * Fills provider/model/temperature defaults so RuntimePolicy always carries a complete selection.
     */
    public ModelSelection resolveSelection(ModelSelection agentSelection) {
        String providerId = agentSelection != null
                ? agentSelection.effectiveProviderId(defaultProviderId())
                : defaultProviderId();
        LlmProperties.Provider provider = requireProvider(providerId);

        String modelName = agentSelection != null && !isBlank(agentSelection.getModelName())
                ? agentSelection.getModelName().trim()
                : (!isBlank(provider.getModel()) ? provider.getModel().trim() : defaultModelName());
        if (isBlank(modelName)) {
            throw new IllegalArgumentException("No model name configured for LLM provider '" + providerId + "'");
        }

        Double temperature = agentSelection != null && agentSelection.getTemperature() != null
                ? agentSelection.getTemperature()
                : (provider.getTemperature() != null ? provider.getTemperature() : springTemperature);

        Integer maxTokens = agentSelection != null ? agentSelection.getMaxTokens() : null;

        return ModelSelection.builder()
                .providerId(providerId)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    public ChatModel getChatModel(ModelSelection selection) {
        ModelSelection effective = resolveSelection(selection);
        String cacheKey = effective.fingerprint();
        return modelCache.computeIfAbsent(cacheKey, key -> createChatModel(effective));
    }

    public LlmProperties.Provider requireProvider(String providerId) {
        LlmProperties.Provider provider = providerOrNull(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId
                    + ". Configure llm.providers." + providerId + " or use default '" + defaultProviderId() + "'");
        }
        return provider;
    }

    private LlmProperties.Provider providerOrNull(String providerId) {
        if (isBlank(providerId) || llmProperties.getProviders() == null) {
            return null;
        }
        return llmProperties.getProviders().get(providerId.trim());
    }

    private ChatModel createChatModel(ModelSelection selection) {
        LlmProperties.Provider provider = requireProvider(selection.getProviderId());
        String type = provider.getType() != null ? provider.getType().trim().toLowerCase(Locale.ROOT) : TYPE_OPENAI_COMPATIBLE;
        if (!TYPE_OPENAI_COMPATIBLE.equals(type) && !"openai".equals(type)) {
            throw new IllegalArgumentException("Unsupported LLM provider type '" + provider.getType()
                    + "' for provider '" + selection.getProviderId() + "'. v1 only supports openai-compatible");
        }
        if (isBlank(provider.getBaseUrl())) {
            throw new IllegalArgumentException("LLM provider '" + selection.getProviderId() + "' is missing base-url");
        }

        OpenAiApi api = apiCache.computeIfAbsent(apiCacheKey(provider), key -> buildApi(provider));
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(selection.getModelName());
        if (selection.getTemperature() != null) {
            options.temperature(selection.getTemperature());
        }
        if (selection.getMaxTokens() != null) {
            options.maxTokens(selection.getMaxTokens());
        }

        log.debug("Creating ChatModel provider={} model={}", selection.getProviderId(), selection.getModelName());
        RetryTemplate retryTemplate = resolveRetryTemplate();
        ObservationRegistry observationRegistry = resolveObservationRegistry();
        return new HuskyOpenAiChatModel(
                api,
                options.build(),
                toolCallingManager,
                retryTemplate,
                observationRegistry);
    }

    private RetryTemplate resolveRetryTemplate() {
        RetryTemplate retry = retryTemplateProvider != null ? retryTemplateProvider.getIfAvailable() : null;
        return retry != null ? retry : RetryUtils.DEFAULT_RETRY_TEMPLATE;
    }

    private ObservationRegistry resolveObservationRegistry() {
        ObservationRegistry registry = observationRegistryProvider != null
                ? observationRegistryProvider.getIfAvailable()
                : null;
        return registry != null ? registry : ObservationRegistry.NOOP;
    }

    private OpenAiApi buildApi(LlmProperties.Provider provider) {
        AgentConfig.LlmConfig llm = agentConfig.getLlm();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(llm.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofMinutes(llm.getReadTimeoutMinutes()));

        String path = !isBlank(provider.getCompletionsPath())
                ? provider.getCompletionsPath().trim()
                : "/v1/chat/completions";
        String apiKey = provider.getApiKey() != null ? provider.getApiKey() : "";

        return OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl().trim())
                .apiKey(apiKey)
                .completionsPath(path)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }

    private String apiCacheKey(LlmProperties.Provider provider) {
        return String.join("|",
                nullToEmpty(provider.getType()),
                nullToEmpty(provider.getBaseUrl()),
                nullToEmpty(provider.getApiKey()),
                nullToEmpty(provider.getCompletionsPath()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }
}
