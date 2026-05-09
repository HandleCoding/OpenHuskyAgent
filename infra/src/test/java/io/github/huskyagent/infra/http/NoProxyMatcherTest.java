package io.github.huskyagent.infra.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class NoProxyMatcherTest {

    private final NoProxyMatcher matcher = new NoProxyMatcher();

    @Test
    void matchesWildcard() {
        assertTrue(matcher.matches("*", URI.create("https://api.search.brave.com")));
    }

    @Test
    void matchesExactHost() {
        assertTrue(matcher.matches("api.search.brave.com", URI.create("https://api.search.brave.com")));
    }

    @Test
    void matchesDomainSuffix() {
        assertTrue(matcher.matches("brave.com", URI.create("https://api.search.brave.com")));
    }

    @Test
    void matchesLeadingDotDomain() {
        assertTrue(matcher.matches(".brave.com", URI.create("https://api.search.brave.com")));
        assertTrue(matcher.matches(".brave.com", URI.create("https://brave.com")));
    }

    @Test
    void matchesWildcardSubdomainOnly() {
        assertTrue(matcher.matches("*.brave.com", URI.create("https://api.search.brave.com")));
        assertFalse(matcher.matches("*.brave.com", URI.create("https://brave.com")));
    }

    @Test
    void matchesHostPort() {
        assertTrue(matcher.matches("api.search.brave.com:443", URI.create("https://api.search.brave.com:443")));
        assertFalse(matcher.matches("api.search.brave.com:8443", URI.create("https://api.search.brave.com:443")));
    }

    @Test
    void matchesIpv4Exact() {
        assertTrue(matcher.matches("192.168.1.10", URI.create("http://192.168.1.10")));
    }

    @Test
    void matchesCidr() {
        assertTrue(matcher.matches("192.168.1.0/24", URI.create("http://192.168.1.10")));
        assertFalse(matcher.matches("192.168.2.0/24", URI.create("http://192.168.1.10")));
    }

    @Test
    void normalizesHostCaseAndTrailingDot() {
        assertTrue(matcher.matches("BRAVE.COM.", URI.create("https://API.SEARCH.BRAVE.COM.")));
    }
}
