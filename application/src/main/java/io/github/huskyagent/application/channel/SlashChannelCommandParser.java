package io.github.huskyagent.application.channel;

import io.github.huskyagent.infra.channel.InboundMessage;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class SlashChannelCommandParser implements ChannelCommandParser {

    @Override
    public Optional<ChannelCommand> parse(InboundMessage inbound) {
        if (inbound == null || inbound.getText() == null) {
            return Optional.empty();
        }
        String text = inbound.getText().trim();
        if (!text.startsWith("/") || text.length() == 1) {
            return Optional.empty();
        }
        String body = text.substring(1).trim();
        int split = body.indexOf(' ');
        String name = (split >= 0 ? body.substring(0, split) : body).toLowerCase(Locale.ROOT);
        String args = split >= 0 ? body.substring(split + 1).trim() : "";
        return Optional.of(new ChannelCommand(name, args, text));
    }
}
