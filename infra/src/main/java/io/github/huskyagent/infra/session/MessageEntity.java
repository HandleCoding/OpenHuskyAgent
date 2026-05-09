package io.github.huskyagent.infra.session;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageEntity {
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private String checkpointId;
    private LocalDateTime createdAt;
}