package io.github.huskyagent.infra.llm;

import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.llm.api.LlmProtocol;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.llm.transport.LlmTransportFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@link ModelSelection} to a cached {@link LlmTransport}.
 * Seeds a {@code main} provider from {@code spring.ai.openai.*} / {@code OPENAI_*} when not explicitly configured.
 */
@Slf4j
@Component
public class LlmClientRegistry {

    public static final String MAIN_PROVIDER = "main";
    public static final String TYPE_OPENAI_COMPATIBLE = "openai-compatible";

    private final LlmProperties llmProperties;
    private final AgentConfig agentConfig;
    private final LlmTransportFactory transportFactory;

    private final String springBaseUrl;
    private final String springApiKey;
    private final String springCompletionsPath;
    private final String springModel;
    private final Double springTemperature;

    private final ConcurrentHashMap<String, LlmTransport> transportCache = new ConcurrentHashMap<>();

    public LlmClientRegistry(
            LlmProperties llmProperties,
            AgentConfig agentConfig,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String springBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String springApiKey,
            @Value("${spring.ai.openai.completions-path:/v1/chat/completions}") String springCompletionsPath,
            @Value("${spring.ai.openai.chat.options.model:${OPENAI_MODEL:gpt-4o}}") String springModel,
            @Value("${spring.ai.openai.chat.options.temperature:0.7}") Double springTemperature) {
        this.llmProperties = llmProperties;
        this.agentConfig = agentConfig;
        this.springBaseUrl = springBaseUrl;
        this.springApiKey = springApiKey;
        this.springCompletionsPath = springCompletionsPath;
        this.springModel = springModel;
        this.springTemperature = springTemperature;
        this.transportFactory = new LlmTransportFactory();
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
        if (isBlank(existing.getType()) && isBlank(existing.getProtocol())) {
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

    /**
     * Protocol-specific HTTP transport for model calls.
     */
    public LlmTransport getTransport(ModelSelection selection) {
        ModelSelection effective = resolveSelection(selection);
        LlmProperties.Provider provider = requireProvider(effective.getProviderId());
        String cacheKey = transportCacheKey(provider);
        return transportCache.computeIfAbsent(cacheKey, key -> createTransport(provider));
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

    private LlmTransport createTransport(LlmProperties.Provider provider) {
        if (isBlank(provider.getBaseUrl())) {
            throw new IllegalArgumentException("LLM provider is missing base-url");
        }
        LlmProtocol protocol = LlmProtocol.fromConfig(provider.effectiveProtocolConfig());
        Duration timeout = Duration.ofMinutes(Math.max(1, agentConfig.getLlm().getReadTimeoutMinutes()));
        LlmTransportFactory.ProviderEndpoint endpoint = new LlmTransportFactory.ProviderEndpoint(
                protocol,
                provider.getBaseUrl().trim(),
                provider.getApiKey() != null ? provider.getApiKey() : "",
                provider.getCompletionsPath(),
                provider.getMessagesPath(),
                provider.getAnthropicVersion(),
                timeout);
        log.info("Creating LlmTransport protocol={} baseUrl={}", protocol, provider.getBaseUrl());
        return transportFactory.create(endpoint);
    }

    private String transportCacheKey(LlmProperties.Provider provider) {
        return String.join("|",
                nullToEmpty(provider.effectiveProtocolConfig()),
                nullToEmpty(provider.getBaseUrl()),
                nullToEmpty(provider.getApiKey()),
                nullToEmpty(provider.getCompletionsPath()),
                nullToEmpty(provider.getMessagesPath()),
                nullToEmpty(provider.getAnthropicVersion()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }
}
