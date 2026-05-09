package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.MessageAttachment;

import java.util.List;

public interface MultimodalChannelAdapter extends ChannelAdapter {

    ChannelInboundContent normalizeContent(Object rawEvent, ChannelAuthContext authContext);

    List<MessageAttachment> downloadAttachments(List<ChannelMediaReference> references);
}
