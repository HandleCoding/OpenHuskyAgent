package io.github.huskyagent.infra.http;

import io.github.huskyagent.infra.config.ProxyProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class ProxyResolverTest {

    private static final URI BRAVE_URI = URI.create("https://api.search.brave.com/res/v1/web/search");

    @Test
    void serviceOverrideBeatsGlobalConfig() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://global.example:8080");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        options.setUrl("http://brave.example:8081");

        ProxySpec proxy = resolver.resolve(BRAVE_URI, options).orElseThrow();

        assertEquals("brave.example", proxy.host());
        assertEquals(8081, proxy.port());
    }

    @Test
    void globalConfigBeatsEnvironment() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://global.example:8080");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        ProxySpec proxy = resolver.resolve(BRAVE_URI).orElseThrow();

        assertEquals("global.example", proxy.host());
    }

    @Test
    void noProxyBypassesConfiguredProxy() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://global.example:8080");
        properties.setNoProxy("api.search.brave.com");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        assertTrue(resolver.resolve(BRAVE_URI).isEmpty());
    }

    @Test
    void serviceNoProxyBypassesServiceOverride() {
        ProxyProperties properties = new ProxyProperties();
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());
        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        options.setUrl("http://brave.example:8081");
        options.setNoProxy("api.search.brave.com");

        assertTrue(resolver.resolve(BRAVE_URI, options).isEmpty());
    }

    @Test
    void disabledServiceProxyDoesNotInheritGlobalProxy() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://global.example:8080");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());
        ProxyResolver.ServiceProxyOptions options = ProxyResolver.ServiceProxyOptions.inherit();
        options.setEnabled(false);

        assertTrue(resolver.resolve(BRAVE_URI, options).isEmpty());
    }

    @Test
    void disabledGlobalProxyReturnsEmpty() {
        ProxyProperties properties = new ProxyProperties();
        properties.setEnabled(false);
        properties.setUrl("http://global.example:8080");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        assertTrue(resolver.resolve(BRAVE_URI).isEmpty());
    }

    @Test
    void parsesBareHostPortAsHttpProxy() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("127.0.0.1:7890");
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        ProxySpec proxy = resolver.resolve(BRAVE_URI).orElseThrow();

        assertEquals("http", proxy.scheme());
        assertEquals("127.0.0.1", proxy.host());
        assertEquals(7890, proxy.port());
    }

    @Test
    void unsupportedSchemeReturnsEmpty() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("socks5://127.0.0.1:7890");
        properties.setEnvEnabled(false);
        ProxyResolver resolver = new ProxyResolver(properties, new NoProxyMatcher());

        assertTrue(resolver.resolve(BRAVE_URI).isEmpty());
    }
}
