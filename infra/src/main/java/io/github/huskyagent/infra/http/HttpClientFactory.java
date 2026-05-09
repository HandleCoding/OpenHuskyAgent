package io.github.huskyagent.infra.http;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpClientFactory {

    private final ProxyResolver proxyResolver;

    public HttpClient create(URI targetUri, Duration timeout) {
        return create(targetUri, timeout, ProxyResolver.ServiceProxyOptions.inherit());
    }

    public HttpClient create(URI targetUri, Duration timeout, ProxyResolver.ServiceProxyOptions options) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout);

        proxyResolver.resolve(targetUri, options).ifPresent(proxy -> {
            log.debug("Using proxy {} for {}", proxy.redactedUrl(), targetUri.getHost());
            builder.proxy(ProxySelector.of(proxy.address()));
        });

        return builder.build();
    }

    public HttpClient createFollowRedirects(URI targetUri, Duration timeout, ProxyResolver.ServiceProxyOptions options) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL);

        proxyResolver.resolve(targetUri, options).ifPresent(proxy -> {
            log.debug("Using proxy {} for {}", proxy.redactedUrl(), targetUri.getHost());
            builder.proxy(ProxySelector.of(proxy.address()));
        });

        return builder.build();
    }
}
