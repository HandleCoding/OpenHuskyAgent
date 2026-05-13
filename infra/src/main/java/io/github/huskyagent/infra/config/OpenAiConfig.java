package io.github.huskyagent.infra.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.HuskyOpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class OpenAiConfig {

    private final AgentConfig agentConfig;

    public OpenAiConfig(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    @Bean
    @Primary
    public OpenAiApi openAiApi(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.completions-path:/v1/chat/completions}") String completionsPath) {

        AgentConfig.LlmConfig llm = agentConfig.getLlm();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(llm.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofMinutes(llm.getReadTimeoutMinutes()));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(completionsPath)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    @Bean
    @Primary
    public HuskyOpenAiChatModel chatModel(
            OpenAiApi openAiApi,
            @Value("${spring.ai.openai.chat.options.model:${OPENAI_MODEL:gpt-4o}}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:0.7}") Double temperature,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new HuskyOpenAiChatModel(
                openAiApi,
                options,
                toolCallingManager,
                retryTemplateProvider.getIfUnique(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE),
                observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP));
    }
}