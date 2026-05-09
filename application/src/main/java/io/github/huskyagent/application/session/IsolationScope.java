package io.github.huskyagent.application.session;

import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IsolationScope {

    String sessionId;
    String principalId;
    String channelType;
    String conversationType;
    String platformAccountId;
    String chatId;
    String threadId;
    String senderId;
    String sceneId;

    public static IsolationScope from(String sessionId, Principal principal,
                                      ChannelIdentity channelIdentity, SceneConfig sceneConfig) {
        return IsolationScope.builder()
                .sessionId(sessionId)
                .principalId(principal != null ? principal.getId() : null)
                .channelType(channelIdentity != null && channelIdentity.getChannelType() != null
                        ? channelIdentity.getChannelType().getName() : null)
                .conversationType(channelIdentity != null && channelIdentity.getConversationType() != null
                        ? channelIdentity.getConversationType().getName() : null)
                .platformAccountId(channelIdentity != null ? channelIdentity.getPlatformAccountId() : null)
                .chatId(channelIdentity != null ? channelIdentity.getChatId() : null)
                .threadId(channelIdentity != null ? channelIdentity.getThreadId() : null)
                .senderId(channelIdentity != null ? channelIdentity.getSenderId() : null)
                .sceneId(sceneConfig != null ? sceneConfig.getSceneId() : null)
                .build();
    }
}
