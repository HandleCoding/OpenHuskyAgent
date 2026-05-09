package io.github.huskyagent.infra.session;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ToolCallEntity {
    private Long id;
    private Long messageId;
    private String toolName;
    private String toolArgs;
    private String result;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}