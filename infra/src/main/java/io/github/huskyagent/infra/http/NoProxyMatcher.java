package io.github.huskyagent.infra.http;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class NoProxyMatcher {

    public boolean matches(String rules, URI targetUri) {
        if (rules == null || rules.isBlank() || targetUri == null || targetUri.getHost() == null) {
            return false;
        }
        String host = normalizeHost(targetUri.getHost());
        int port = targetUri.getPort();
        return Arrays.stream(rules.split(","))
            .map(String::trim)
            .filter(rule -> !rule.isEmpty())
            .anyMatch(rule -> matchesRule(rule, host, port));
    }

    public boolean matches(List<String> rules, URI targetUri) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        return matches(String.join(",", rules), targetUri);
    }

    private boolean matchesRule(String rawRule, String host, int port) {
        String rule = rawRule.toLowerCase(Locale.ROOT).trim();
        if (rule.equals("*")) {
            return true;
        }

        HostPort ruleHostPort = splitHostPort(rule);
        if (ruleHostPort.port() != null && ruleHostPort.port() != port) {
            return false;
        }

        String ruleHost = normalizeHost(ruleHostPort.host());
        if (ruleHost.isBlank()) {
            return false;
        }

        if (matchesCidr(ruleHost, host) || ruleHost.equals(host)) {
            return true;
        }

        if (ruleHost.startsWith("*.")) {
            String suffix = ruleHost.substring(1);
            return host.endsWith(suffix) && !host.equals(suffix.substring(1));
        }

        if (ruleHost.startsWith(".")) {
            return host.equals(ruleHost.substring(1)) || host.endsWith(ruleHost);
        }

        return host.endsWith("." + ruleHost);
    }

    private boolean matchesCidr(String ruleHost, String host) {
        int slash = ruleHost.indexOf('/');
        if (slash < 0) {
            return false;
        }
        try {
            long target = ipv4ToLong(host);
            long network = ipv4ToLong(ruleHost.substring(0, slash));
            int prefix = Integer.parseInt(ruleHost.substring(slash + 1));
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long mask = prefix == 0 ? 0 : 0xffffffffL << (32 - prefix) & 0xffffffffL;
            return (target & mask) == (network & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private long ipv4ToLong(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address");
        }
        long result = 0;
        for (String part : parts) {
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            result = (result << 8) | value;
        }
        return result;
    }

    private HostPort splitHostPort(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                String host = value.substring(1, close);
                Integer port = parsePort(value.substring(close + 1));
                return new HostPort(host, port);
            }
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            Integer port = parsePort(value.substring(colon + 1));
            if (port != null) {
                return new HostPort(value.substring(0, colon), port);
            }
        }
        return new HostPort(value, null);
    }

    private Integer parsePort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String portValue = value.startsWith(":") ? value.substring(1) : value;
        try {
            return Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record HostPort(String host, Integer port) {}
}
