package io.github.huskyagent.application.channel.binding;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigChannelBindingResolver implements ChannelBindingResolver {
    private final AgentChannelBindingProperties properties;
    private final ChannelInstanceReferenceResolver referenceResolver;
    private volatile Map<String, ChannelInstanceBinding> bindingsByChannelKey = Map.of();

    @PostConstruct
    public void validate() {
        bindingsByChannelKey = buildBindings();
    }

    @Override
    public Optional<ChannelInstanceBinding> resolve(ChannelIdentity identity) {
        return resolveConfigured(identity).filter(ChannelInstanceBinding::enabled);
    }

    @Override
    public Optional<ChannelInstanceBinding> resolveConfigured(ChannelIdentity identity) {
        if (identity == null || identity.getChannelType() == null || isBlank(identity.getPlatformAccountId())) {
            return Optional.empty();
        }
        Map<String, ChannelInstanceBinding> bindings = effectiveBindings();
        if (bindings.isEmpty()) {
            return Optional.empty();
        }
        for (ChannelInstanceBinding binding : bindings.values()) {
            if (binding.channelType() == identity.getChannelType()
                    && normalizePlatformAccountId(identity.getChannelType(), binding.platformAccountId())
                    .equals(normalizePlatformAccountId(identity.getChannelType(), identity.getPlatformAccountId()))) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    private Map<String, ChannelInstanceBinding> effectiveBindings() {
        Map<String, ChannelInstanceBinding> current = bindingsByChannelKey;
        if (!current.isEmpty()) {
            return current;
        }
        current = buildBindings();
        bindingsByChannelKey = current;
        return current;
    }

    private Map<String, ChannelInstanceBinding> buildBindings() {
        Map<String, ChannelInstanceBinding> result = new LinkedHashMap<>();
        if (properties.getBindings() == null || properties.getBindings().isEmpty()) {
            return Map.of();
        }
        for (Map.Entry<String, java.util.List<String>> entry : properties.getBindings().entrySet()) {
            String agentId = entry.getKey();
            if (isBlank(agentId)) {
                throw new IllegalArgumentException("agent-channel-bindings contains a blank agent id");
            }
            if (entry.getValue() == null) {
                continue;
            }
            for (String ref : entry.getValue()) {
                ChannelInstanceBinding binding = toBinding(agentId, ref).orElse(null);
                if (binding == null) {
                    continue;
                }
                String key = channelKey(binding.channelType(), binding.platformAccountId());
                ChannelInstanceBinding existing = result.get(key);
                if (existing != null && !existing.sceneId().equals(binding.sceneId())) {
                    throw new IllegalArgumentException("Channel instance is bound to multiple agents: "
                            + binding.channelType().getName() + ":" + binding.platformAccountId()
                            + " -> " + existing.sceneId() + ", " + binding.sceneId());
                }
                result.put(key, binding);
            }
        }
        return Map.copyOf(result);
    }

    private Optional<ChannelInstanceBinding> toBinding(String agentId, String ref) {
        ParsedChannelRef parsed = parseChannelRef(ref);
        ChannelInstanceReference reference = referenceResolver.resolve(parsed.channelType(), parsed.instanceId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel instance in agent-channel-bindings: " + ref));
        if (!reference.enabled()) {
            return Optional.empty();
        }
        if (isBlank(reference.platformAccountId())) {
            throw new IllegalArgumentException("Channel instance has blank platform account id: " + ref);
        }
        String bindingId = agentId + "@" + ref;
        return Optional.of(new ChannelInstanceBinding(
                bindingId,
                reference.channelType(),
                reference.platformAccountId(),
                agentId,
                true,
                agentId,
                Map.of("channelRef", ref, "agentId", agentId, "channelInstanceId", reference.instanceId())
        ));
    }

    private ParsedChannelRef parseChannelRef(String ref) {
        if (isBlank(ref)) {
            throw new IllegalArgumentException("Blank channel ref in agent-channel-bindings");
        }
        String[] parts = ref.split(":", -1);
        if (parts.length != 2 || isBlank(parts[0]) || isBlank(parts[1])) {
            throw new IllegalArgumentException("Invalid channel ref in agent-channel-bindings: " + ref);
        }
        ChannelType channelType = parseChannelType(parts[0])
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel type in agent-channel-bindings: " + ref));
        return new ParsedChannelRef(channelType, parts[1].trim());
    }

    private Optional<ChannelType> parseChannelType(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (ChannelType type : ChannelType.values()) {
            if (type.name().equals(normalized) || type.getName().equalsIgnoreCase(value.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private String channelKey(ChannelType channelType, String platformAccountId) {
        return channelType.getName() + ":" + normalizePlatformAccountId(channelType, platformAccountId);
    }

    private String normalizePlatformAccountId(ChannelType channelType, String value) {
        if (channelType != ChannelType.TELEGRAM || value == null) {
            return value;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ParsedChannelRef(ChannelType channelType, String instanceId) {
    }
}
