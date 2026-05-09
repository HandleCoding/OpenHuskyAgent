package io.github.huskyagent.infra.channel;

public enum ChannelType {

    TUI("tui", "local TUI personal assistant"),
    FEISHU("feishu", "Feishu bot"),
    TELEGRAM("telegram", "Telegram bot"),
    HTTP("http", "HTTP Chatbot");

    private final String name;
    private final String description;

    ChannelType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
}