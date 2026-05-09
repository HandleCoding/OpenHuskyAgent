package io.github.huskyagent.service.auth;

import io.github.huskyagent.infra.auth.AuthConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthFilterConfig {

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(AuthConfig authConfig) {
        return ApiKeyAuthFilter.registrationBean(authConfig);
    }
}