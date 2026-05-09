package io.github.huskyagent.infra.http;

import io.github.huskyagent.infra.config.ProxyProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HttpClientFactoryTest {

    private static final URI TARGET_URI = URI.create("https://api.search.brave.com/res/v1/web/search");

    @Test
    void appliesProxySelectorWhenProxyResolves() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://127.0.0.1:7890");
        HttpClientFactory factory = new HttpClientFactory(new ProxyResolver(properties, new NoProxyMatcher()));

        HttpClient client = factory.create(TARGET_URI, Duration.ofSeconds(10));

        assertTrue(client.proxy().isPresent());
        assertEquals(Duration.ofSeconds(10), client.connectTimeout().orElseThrow());
    }

    @Test
    void doesNotApplyProxySelectorWhenBypassed() {
        ProxyProperties properties = new ProxyProperties();
        properties.setUrl("http://127.0.0.1:7890");
        properties.setNoProxy("api.search.brave.com");
        HttpClientFactory factory = new HttpClientFactory(new ProxyResolver(properties, new NoProxyMatcher()));

        HttpClient client = factory.create(TARGET_URI, Duration.ofSeconds(10));

        assertTrue(client.proxy().isEmpty());
    }

    @Test
    void preservesRedirectSetting() {
        ProxyProperties properties = new ProxyProperties();
        HttpClientFactory factory = new HttpClientFactory(new ProxyResolver(properties, new NoProxyMatcher()));

        HttpClient client = factory.createFollowRedirects(TARGET_URI, Duration.ofSeconds(10), ProxyResolver.ServiceProxyOptions.inherit());

        assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
    }
}
