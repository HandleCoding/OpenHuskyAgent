package io.github.huskyagent.service.auth;

import io.github.huskyagent.infra.auth.AuthConfig;
import io.github.huskyagent.service.openai.OpenAiCompatibleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthFilterConfig {

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            AuthConfig authConfig,
            OpenAiCompatibleProperties openAiProperties) {
        return ApiKeyAuthFilter.registrationBean(authConfig, openAiProperties);
    }
}
