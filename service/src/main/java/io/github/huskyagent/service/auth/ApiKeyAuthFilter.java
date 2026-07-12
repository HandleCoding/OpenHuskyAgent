package io.github.huskyagent.service.auth;

import io.github.huskyagent.infra.auth.AuthConfig;
import io.github.huskyagent.infra.auth.AuthContext;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AuthConfig authConfig;

    public ApiKeyAuthFilter(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/api/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader("X-Api-Key");
        String userId = request.getHeader("X-User-Id");

        if (apiKey == null || apiKey.isBlank()) {
            sendUnauthorized(response, "Missing X-Api-Key header");
            return;
        }

        if (!authConfig.getApiKeys().contains(apiKey)) {
            sendUnauthorized(response, "Invalid API key");
            return;
        }

        if (userId == null || userId.isBlank()) {
            sendUnauthorized(response, "Missing X-User-Id header");
            return;
        }

        Principal principal = Principal.builder()
                .id("api:" + userId)
                .displayName(userId)
                .channelType(ChannelType.HTTP)
                .build();

        AuthContext.set(userId);
        PrincipalContext.set(principal);
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
            PrincipalContext.clear();
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + escape(reason) + "\"}");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static FilterRegistrationBean<ApiKeyAuthFilter> registrationBean(AuthConfig authConfig) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(authConfig));
        registration.addUrlPatterns("/api/chat", "/api/chat/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("apiKeyAuthFilter");
        return registration;
    }
}
