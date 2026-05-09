package io.github.huskyagent.service.channel.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

@Slf4j
public class FeishuInstanceEventHandler {

    private final FeishuProperties.InstanceProperties properties;
    private final FeishuInstanceAdapter adapter;
    private final ChannelRuntimeService runtimeService;
    private final Executor agentExecutor;
    private final FeishuInboundDeduplicator deduplicator;
    private final EventDispatcher eventDispatcher;

    public FeishuInstanceEventHandler(FeishuProperties.InstanceProperties properties,
                                      FeishuInstanceAdapter adapter,
                                      ChannelRuntimeService runtimeService,
                                      Executor agentExecutor,
                                      FeishuInboundDeduplicator deduplicator) {
        this.properties = properties;
        this.adapter = adapter;
        this.runtimeService = runtimeService;
        this.agentExecutor = agentExecutor;
        this.deduplicator = deduplicator;
        this.eventDispatcher = EventDispatcher.newBuilder(
                        properties.getVerificationToken(),
                        properties.getEncryptKey()
                )
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        EventMessage message = event.getEvent() != null ? event.getEvent().getMessage() : null;
                        log.info("Received Feishu message event: messageId={}, chatId={}, chatType={}, messageType={}",
                                message != null ? message.getMessageId() : null,
                                message != null ? message.getChatId() : null,
                                message != null ? message.getChatType() : null,
                                message != null ? message.getMessageType() : null);
                        handleMessageEvent(event, Map.of(), null);
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                        return handleInteractiveCard(event);
                    }
                })
                .build();
    }

    public EventDispatcher larkEventDispatcher() {
        return eventDispatcher;
    }

    P2CardActionTriggerResponse handleInteractiveCard(P2CardActionTrigger event) {
        Map<String, Object> value = event != null && event.getEvent() != null && event.getEvent().getAction() != null
                ? event.getEvent().getAction().getValue()
                : Map.of();
        String kind = stringValue(value.get("kind"));
        String requestId = stringValue(value.get("requestId"));
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        boolean handled;
        String successMessage;
        String staleMessage;
        if ("husky_approval".equals(kind)) {
            String decision = stringValue(value.get("decision"));
            handled = requestId != null
                    && decision != null
                    && adapter.completeApproval(requestId, decision);
            successMessage = "Approval submitted";
            staleMessage = "Approval request is no longer pending";
        } else if ("husky_clarify".equals(kind)) {
            String answer = clarifyAnswer(event, value);
            handled = requestId != null && adapter.completeClarify(requestId, answer);
            successMessage = "Answer submitted";
            staleMessage = "Clarification request is no longer pending";
        } else {
            handled = false;
            successMessage = "Submitted";
            staleMessage = "Request is no longer pending";
        }
        toast.setType(handled ? "success" : "warning");
        toast.setContent(handled ? successMessage : staleMessage);
        response.setToast(toast);
        return response;
    }

    private String clarifyAnswer(P2CardActionTrigger event, Map<String, Object> value) {
        String answer = stringValue(value.get("answer"));
        if (answer != null) {
            return answer;
        }
        if (event == null || event.getEvent() == null || event.getEvent().getAction() == null) {
            return "";
        }
        Map<String, Object> formValue = event.getEvent().getAction().getFormValue();
        answer = formValue != null ? stringValue(formValue.get("answer")) : null;
        if (answer != null) {
            return answer;
        }
        answer = event.getEvent().getAction().getInputValue();
        return answer != null ? answer : "";
    }

    private String platformAccountId() {
        if (properties == null) {
            return null;
        }
        String appId = properties.getAppId();
        if (appId != null && !appId.isBlank()) {
            return appId;
        }
        return properties.getBotOpenId();
    }

    private String stringValue(Object value) {
        return value != null ? Objects.toString(value, null) : null;
    }

    public void handleMessageEvent(P2MessageReceiveV1 event,
                                   Map<String, String> headers,
                                   String rawBody) {
        ChannelAuthContext authContext = ChannelAuthContext.builder()
                .headers(headers)
                .rawBody(rawBody)
                .build();
        InboundMessage inbound = adapter.normalizeInbound(event, authContext);
        if (inbound.isIgnored()) {
            log.info("Ignored Feishu message event");
            return;
        }
        if (deduplicator.isDuplicate(platformAccountId(), inbound.getMessageId())) {
            log.info("Dropped duplicate Feishu message: platformAccountId={}, messageId={}",
                    platformAccountId(), inbound.getMessageId());
            return;
        }
        log.info("Dispatching Feishu inbound message: sessionScene={}, principal={}, chatId={}, textLength={}",
                inbound.getSceneId(),
                inbound.getPrincipal() != null ? inbound.getPrincipal().getId() : null,
                inbound.getChannelIdentity() != null ? inbound.getChannelIdentity().getChatId() : null,
                inbound.getText() != null ? inbound.getText().length() : 0);
        runtimeService.handleInboundAsync(inbound, adapter, agentExecutor)
                .exceptionally(error -> {
                    log.error("Feishu inbound handling failed", error);
                    return null;
                });
    }
}