package io.github.huskyagent.infra.session;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SessionEntity {
    private String id;
    private String userId;
    private String ownerPrincipalId;
    private String channelType;
    private String sceneId;
    private String conversationType;
    private String sourceChatId;
    private String sourceThreadId;
    private String sourceSenderId;
    private String sessionKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String status;
    private String metadata;
    private int inputTokens;
    private int outputTokens;
    private long totalDurationMs;
}