package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ChannelAdapterRegistry {

    private final Map<ChannelType, ChannelAdapter> adapters = new EnumMap<>(ChannelType.class);

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        for (ChannelAdapter adapter : adapters) {
            ChannelAdapter existing = this.adapters.putIfAbsent(adapter.channelType(), adapter);
            if (existing != null) {
                throw new IllegalStateException("Duplicate channel adapter: " + adapter.channelType());
            }
        }
    }

    public Optional<ChannelAdapter> find(ChannelType channelType) {
        return Optional.ofNullable(adapters.get(channelType));
    }

    public ChannelAdapter get(ChannelType channelType) {
        return find(channelType)
                .orElseThrow(() -> new IllegalArgumentException("No channel adapter registered for " + channelType));
    }
}
