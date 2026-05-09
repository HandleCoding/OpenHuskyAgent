package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.Principal;

public class ChannelContextSection extends AbstractPromptSection {

    @Override
    public String getName() {
        return "channel_context";
    }

    @Override
    public int getPriority() {
        return 150;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public String build(PromptContext context) {
        ChannelIdentity identity = context.getChannelIdentity().orElse(null);
        Principal principal = context.getPrincipal().orElse(null);
        String sceneId = context.getSceneId().orElse(null);

        if (identity == null && principal == null && sceneId == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Current Session Context\n\n");

        String platform = identity != null && identity.getChannelType() != null
                ? identity.getChannelType().getName() : "Unknown";
        String convType = identity != null && identity.getConversationType() != null
                ? identity.getConversationType().getName() : "direct";
        sb.append("**Platform:** ").append(platform).append(" (").append(friendlyConvType(convType)).append(")\n");

        if (principal != null) {
            String displayName = principal.getDisplayName();
            String principalId = principal.getId();
            if (displayName != null && !displayName.isBlank()) {
                sb.append("**User:** ").append(displayName).append("\n");
            } else if (principalId != null && !principalId.isBlank()) {
                sb.append("**User ID:** ").append(principalId).append("\n");
            }
        } else if (identity != null && identity.getSenderId() != null && !identity.getSenderId().isBlank()) {
            sb.append("**User ID:** ").append(identity.getSenderId()).append("\n");
        }

        if (identity != null) {
            String chatId = identity.getChatId();
            String senderId = identity.getSenderId();
            if (chatId != null && !chatId.isBlank()
                    && !chatId.equals(senderId)) {
                sb.append("**Chat ID:** ").append(chatId).append("\n");
            }
        }

        if (sceneId != null && !sceneId.isBlank()) {
            sb.append("**Scene:** ").append(sceneId).append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private String friendlyConvType(String convType) {
        return switch (convType.toUpperCase()) {
            case "DIRECT" -> "Direct chat";
            case "GROUP" -> "Group chat";
            case "THREAD" -> "Thread";
            default -> convType;
        };
    }
}