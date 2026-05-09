package io.github.huskyagent.infra.http;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public record ProxySpec(String scheme, String host, int port, String originalUrl) {

    public static Optional<ProxySpec> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        if (!normalized.contains("://")) {
            normalized = "http://" + normalized;
        }

        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        int port = uri.getPort();
        if (port < 0) {
            port = scheme.equals("https") ? 443 : 80;
        }

        return Optional.of(new ProxySpec(scheme, host, port, normalized));
    }

    public InetSocketAddress address() {
        return InetSocketAddress.createUnresolved(host, port);
    }

    public String redactedUrl() {
        try {
            URI uri = URI.create(originalUrl);
            String authority = uri.getRawAuthority();
            if (authority != null && authority.contains("@")) {
                authority = "***@" + authority.substring(authority.indexOf('@') + 1);
            }
            return new URI(uri.getScheme(), authority, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            return scheme + "://" + host + ":" + port;
        }
    }
}
