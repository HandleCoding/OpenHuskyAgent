package io.github.huskyagent.service.channel.feishu;

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
public class FeishuInstanceRegistry implements ChannelAdapter {

    private final Map<String, FeishuInstance> instances;

    public FeishuInstanceRegistry(FeishuProperties properties,
                                  ObjectMapper objectMapper,
                                  ToolDisplayMessageRenderer toolDisplayMessageRenderer,
                                  ChannelRuntimeService runtimeService,
                                  FeishuInboundDeduplicator deduplicator,
                                  @Qualifier("agentExecutor") Executor agentExecutor) {
        Map<String, FeishuInstance> values = new LinkedHashMap<>();
        if (properties.getInstances() != null) {
            properties.getInstances().forEach((instanceId, instanceProperties) -> {
                if (isBlank(instanceId) || instanceProperties == null) {
                    return;
                }
                FeishuCardRenderer cardRenderer = new FeishuCardRenderer(objectMapper);
                FeishuApiClient apiClient = new FeishuApiClient(instanceProperties, objectMapper, cardRenderer);
                FeishuInstanceAdapter adapter = new FeishuInstanceAdapter(
                        instanceProperties,
                        apiClient,
                        objectMapper,
                        toolDisplayMessageRenderer
                );
                FeishuInstanceEventHandler eventHandler = new FeishuInstanceEventHandler(
                        instanceProperties,
                        adapter,
                        runtimeService,
                        agentExecutor,
                        deduplicator
                );
                values.put(instanceId, new FeishuInstance(
                        instanceId,
                        instanceProperties,
                        apiClient,
                        adapter,
                        eventHandler
                ));
            });
        }
        this.instances = Map.copyOf(values);
        log.info("Loaded Feishu instances: {}", this.instances.keySet());
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder()
                .supportsApprovalCard(true)
                .supportsImageInput(true)
                .supportsEdit(true)
                .supportsStreaming(false)
                .supportsTyping(false)
                .build();
    }

    @Override
    public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
        // Feishu inbound events are dispatched per-instance via FeishuInstanceEventHandler,
        // which calls FeishuInstanceAdapter.normalizeInbound() directly.
        // This registry-level method is never used for actual inbound normalization.
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

    public Map<String, FeishuInstance> all() {
        return instances;
    }

    public Optional<FeishuInstance> find(String instanceId) {
        if (isBlank(instanceId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(instances.get(instanceId));
    }

    private FeishuInstanceAdapter adapter(OutboundMessage message) {
        String platformAccountId = message != null && message.getChannelIdentity() != null
                ? message.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private FeishuInstanceAdapter adapter(ApprovalPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private FeishuInstanceAdapter adapter(ClarifyPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private FeishuInstanceAdapter adapter(SessionRoute route) {
        String platformAccountId = route != null && route.channelIdentity() != null
                ? route.channelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private FeishuInstanceAdapter adapter(String platformAccountId) {
        return instances.values().stream()
                .filter(instance -> platformAccountId.equals(platformAccountId(instance.properties())))
                .map(FeishuInstance::adapter)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Feishu instance adapter for platformAccountId=" + platformAccountId));
    }

    private String platformAccountId(FeishuProperties.InstanceProperties properties) {
        return !isBlank(properties.getAppId()) ? properties.getAppId() : properties.getBotOpenId();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}