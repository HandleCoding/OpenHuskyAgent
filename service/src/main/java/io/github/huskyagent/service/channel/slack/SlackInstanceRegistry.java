package io.github.huskyagent.service.channel.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
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
public class SlackInstanceRegistry implements ChannelAdapter {

    private final Map<String, SlackInstance> instances;

    public SlackInstanceRegistry(SlackProperties properties,
                                 ToolDisplayMessageRenderer toolDisplayMessageRenderer,
                                 ChannelRuntimeService runtimeService,
                                 SlackInboundDeduplicator deduplicator,
                                 @Qualifier("agentExecutor") Executor agentExecutor) {
        Map<String, SlackInstance> values = new LinkedHashMap<>();
        if (properties.getInstances() != null) {
            properties.getInstances().forEach((instanceId, instanceProperties) -> {
                if (isBlank(instanceId) || instanceProperties == null) {
                    return;
                }
                SlackApiClient apiClient = new SlackApiClient(instanceProperties);
                SlackInstanceAdapter adapter = new SlackInstanceAdapter(instanceProperties, apiClient, toolDisplayMessageRenderer);
                SlackInstanceEventHandler eventHandler = new SlackInstanceEventHandler(
                        adapter,
                        runtimeService,
                        agentExecutor,
                        deduplicator
                );
                App app = buildApp(instanceProperties, eventHandler);
                values.put(instanceId, new SlackInstance(instanceId, instanceProperties, apiClient, adapter, eventHandler, app));
            });
        }
        this.instances = Map.copyOf(values);
        log.info("Loaded Slack instances: {}", this.instances.keySet());
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SLACK;
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

    public Map<String, SlackInstance> all() {
        return instances;
    }

    public Optional<SlackInstance> find(String instanceId) {
        if (isBlank(instanceId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(instances.get(instanceId));
    }

    private App buildApp(SlackProperties.InstanceProperties properties, SlackInstanceEventHandler eventHandler) {
        App app = new App(AppConfig.builder()
                .singleTeamBotToken(properties.getBotToken())
                .build());
        app.event(com.slack.api.model.event.AppMentionEvent.class, eventHandler::handleAppMention);
        app.event(com.slack.api.model.event.MessageEvent.class, eventHandler::handleMessage);
        app.blockAction("husky_approval", eventHandler::handleBlockAction);
        app.blockAction("husky_clarify", eventHandler::handleBlockAction);
        return app;
    }

    private SlackInstanceAdapter adapter(OutboundMessage message) {
        String platformAccountId = message != null && message.getChannelIdentity() != null
                ? message.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private SlackInstanceAdapter adapter(ApprovalPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private SlackInstanceAdapter adapter(ClarifyPrompt prompt) {
        String platformAccountId = prompt != null && prompt.getChannelIdentity() != null
                ? prompt.getChannelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private SlackInstanceAdapter adapter(SessionRoute route) {
        String platformAccountId = route != null && route.channelIdentity() != null
                ? route.channelIdentity().getPlatformAccountId()
                : null;
        return adapter(platformAccountId);
    }

    private SlackInstanceAdapter adapter(String platformAccountId) {
        if (isBlank(platformAccountId)) {
            throw new IllegalArgumentException("No Slack instance adapter for blank platformAccountId");
        }
        return instances.values().stream()
                .map(SlackInstance::adapter)
                .filter(adapter -> SlackApiClient.normalizeBotUserId(platformAccountId).equals(adapter.platformAccountId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Slack instance adapter for platformAccountId=" + platformAccountId));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
