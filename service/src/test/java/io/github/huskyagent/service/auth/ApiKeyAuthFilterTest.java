package io.github.huskyagent.service.auth;

import io.github.huskyagent.infra.auth.AuthConfig;
import io.github.huskyagent.infra.auth.PrincipalContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyAuthFilterTest {

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void apiChatStillRequiresUserId() throws Exception {
        ApiKeyAuthFilter filter = newFilter();
        MockHttpServletRequest request = request("/api/chat/");
        request.addHeader("X-Api-Key", "test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing X-User-Id header"));
    }

    @Test
    void apiChatDoesNotAcceptBearerToken() throws Exception {
        ApiKeyAuthFilter filter = newFilter();
        MockHttpServletRequest request = request("/api/chat/");
        request.addHeader("Authorization", "Bearer test-key");
        request.addHeader("X-User-Id", "demo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing X-Api-Key header"));
    }

    @Test
    void apiChatAcceptsValidKeyAndUser() throws Exception {
        ApiKeyAuthFilter filter = newFilter();
        MockHttpServletRequest request = request("/api/chat");
        request.addHeader("X-Api-Key", "test-key");
        request.addHeader("X-User-Id", "demo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.invoked);
        assertEquals("api:demo", chain.principalId);
    }

    @Test
    void registrationCoversApiChatEndpointWithoutTrailingSlash() {
        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(true);

        var registration = ApiKeyAuthFilter.registrationBean(authConfig);

        assertTrue(registration.getUrlPatterns().contains("/api/chat"));
        assertFalse(registration.getUrlPatterns().stream().anyMatch(p -> p.startsWith("/v1")));
    }

    private ApiKeyAuthFilter newFilter() {
        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(true);
        authConfig.setApiKeys(List.of("test-key"));
        return new ApiKeyAuthFilter(authConfig);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    private static class CapturingFilterChain extends MockFilterChain {
        private boolean invoked;
        private String principalId;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            invoked = true;
            principalId = PrincipalContext.get().getId();
        }
    }
}
