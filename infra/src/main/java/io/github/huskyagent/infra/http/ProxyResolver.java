package io.github.huskyagent.infra.http;

import io.github.huskyagent.infra.config.ProxyProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class ProxyResolver {

    private static final String[] PROXY_ENV_KEYS = {
        "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "https_proxy", "http_proxy", "all_proxy"
    };

    private final ProxyProperties properties;
    private final NoProxyMatcher noProxyMatcher;

    public Optional<ProxySpec> resolve(URI targetUri) {
        return resolve(targetUri, ServiceProxyOptions.inherit());
    }

    public Optional<ProxySpec> resolve(URI targetUri, ServiceProxyOptions options) {
        if (targetUri == null) {
            return Optional.empty();
        }
        ServiceProxyOptions serviceOptions = options == null ? ServiceProxyOptions.inherit() : options;
        String noProxyRules = joinRules(serviceOptions.getNoProxy(), properties.getNoProxy(), env("NO_PROXY"), env("no_proxy"));
        if (noProxyMatcher.matches(noProxyRules, targetUri)) {
            return Optional.empty();
        }

        if (serviceOptions.getEnabled() != null && !serviceOptions.getEnabled()) {
            return Optional.empty();
        }

        Optional<ProxySpec> serviceProxy = ProxySpec.parse(serviceOptions.getUrl());
        if (serviceProxy.isPresent()) {
            return serviceProxy;
        }

        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        Optional<ProxySpec> configuredProxy = ProxySpec.parse(properties.getUrl());
        if (configuredProxy.isPresent()) {
            return configuredProxy;
        }

        if (properties.isEnvEnabled()) {
            for (String key : PROXY_ENV_KEYS) {
                Optional<ProxySpec> envProxy = ProxySpec.parse(env(key));
                if (envProxy.isPresent()) {
                    return envProxy;
                }
            }
        }

        return Optional.empty();
    }

    private String env(String key) {
        return System.getenv(key);
    }

    private String joinRules(String... values) {
        return Stream.of(values)
            .filter(value -> value != null && !value.isBlank())
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    }

    @Data
    public static class ServiceProxyOptions {
        private Boolean enabled;
        private String url;
        private String noProxy;

        public static ServiceProxyOptions inherit() {
            return new ServiceProxyOptions();
        }
    }
}
