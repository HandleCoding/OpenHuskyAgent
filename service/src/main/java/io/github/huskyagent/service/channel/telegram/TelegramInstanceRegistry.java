package io.github.huskyagent.service.channel.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.channel.ChannelAdapter;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.RuntimeEvent;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelCapabilities;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class TelegramInstanceRegistry implements ChannelAdapter {

    private final Map<String, TelegramInstance> instances;

    public TelegramInstanceRegistry(TelegramProperties properties,
                                    ObjectMapper objectMapper,
                                    ToolDisplayMessageRenderer toolDisplayMessageRenderer,
                                    ChannelRuntimeService runtimeService,
                                    TelegramInboundDeduplicator deduplicator,
                                    @Qualifier("agentExecutor") Executor agentExecutor) {
        Map<String, TelegramInstance> values = new LinkedHashMap<>();
        if (properties.getInstances() != null) {
            properties.getInstances().forEach((instanceId, instanceProperties) -> {
                if (isBlank(instanceId) || instanceProperties == null) {
                    return;
                }
                TelegramApiClient apiClient = new TelegramApiClient(instanceProperties);
                TelegramInstanceAdapter adapter = new TelegramInstanceAdapter(instanceProperties, apiClient, toolDisplayMessageRenderer);
                TelegramInstanceEventHandler eventHandler = new TelegramInstanceEventHandler(
                        adapter,
                        runtimeService,
                        agentExecutor,
                        deduplicator
                );
                values.put(instanceId, new TelegramInstance(instanceId, instanceProperties, apiClient, adapter, eventHandler));
            });
        }
        this.instances = Map.copyOf(values);
        log.info("Loaded Telegram instances: {}", this.instances.keySet());
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder()
                .supportsApprovalCard(true)
                .supportsEdit(true)
                .supportsTyping(true)
                .supportsStreaming(false)
                .supportsImageInput(false)
                .build();
    }

    @Override
    public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
        return InboundMessage.ignored(rawEvent);
    }

    @Override
    public void send(OutboundMessage message) {
        adapter(message).send(message);
    }

    @Override
    public void typing(OutboundMessage message) {
        adapter(message).typing(message);
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
        return adapter(prompt).requestApproval(prompt);
    }

    @Override
    public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
        return adapter(prompt).requestClarify(prompt);
    }

    @Override
    public void onRuntimeEvent(SessionRoute route, RuntimeEvent event) {
        adapter(route).onRuntimeEvent(route, event);
    }

    public Map<String, TelegramInstance> all() {
        return instances;
    }

    public Optional<TelegramInstance> find(String instanceId) {
        if (isBlank(instanceId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(instances.get(instanceId));
    }

    private TelegramInstanceAdapter adapter(OutboundMessage message) {
        String platformAccountId = message != null && message.getChannelIdentity() != null
                ? message.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private TelegramInstanceAdapter adapter(ApprovalPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private TelegramInstanceAdapter adapter(ClarifyPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private TelegramInstanceAdapter adapter(SessionRoute route) {
        String platformAccountId = route != null && route.channelIdentity() != null
                ? route.channelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private TelegramInstanceAdapter adapter(String platformAccountId) {
        if (isBlank(platformAccountId)) {
            throw new IllegalArgumentException("No Telegram instance adapter for blank platformAccountId");
        }
        return instances.values().stream()
                .map(TelegramInstance::adapter)
                .filter(adapter -> TelegramApiClient.normalizeUsername(platformAccountId).equals(adapter.platformAccountId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Telegram instance adapter for platformAccountId=" + platformAccountId));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
