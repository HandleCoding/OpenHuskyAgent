package io.github.huskyagent.service.auth;

import io.github.huskyagent.infra.auth.AuthConfig;
import io.github.huskyagent.infra.auth.AuthContext;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.service.openai.OpenAiCompatibleProperties;
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

    private final OpenAiCompatibleProperties openAiProperties;

    public ApiKeyAuthFilter(AuthConfig authConfig, OpenAiCompatibleProperties openAiProperties) {
        this.authConfig = authConfig;
        this.openAiProperties = openAiProperties;
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

        boolean openAiRequest = isOpenAiRequest(request);
        String apiKey = apiKey(request, openAiRequest);
        String userId = request.getHeader("X-User-Id");

        if (apiKey == null || apiKey.isBlank()) {
            sendUnauthorized(response, openAiRequest, openAiRequest ? "Missing bearer token" : "Missing X-Api-Key header");
            return;
        }

        if (!authConfig.getApiKeys().contains(apiKey)) {
            sendUnauthorized(response, openAiRequest, "Invalid API key");
            return;
        }

        if (userId == null || userId.isBlank()) {
            if (!openAiRequest) {
                sendUnauthorized(response, false, "Missing X-User-Id header");
                return;
            }
            userId = openAiProperties.getDefaultUserId();
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

    private String apiKey(HttpServletRequest request, boolean openAiRequest) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (!openAiRequest) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private boolean isOpenAiRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        return path != null && path.startsWith("/v1/");
    }

    private void sendUnauthorized(HttpServletResponse response, boolean openAiRequest, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        if (openAiRequest) {
            response.getWriter().write("{\"error\":{\"message\":\"" + escape(reason) + "\",\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}");
        } else {
            response.getWriter().write("{\"error\":\"" + escape(reason) + "\"}");
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static FilterRegistrationBean<ApiKeyAuthFilter> registrationBean(AuthConfig authConfig,
                                                                            OpenAiCompatibleProperties openAiProperties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(authConfig, openAiProperties));
        registration.addUrlPatterns("/api/chat", "/api/chat/*", "/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("apiKeyAuthFilter");
        return registration;
    }
}