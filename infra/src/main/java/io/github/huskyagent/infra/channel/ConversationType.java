package io.github.huskyagent.infra.channel;

public enum ConversationType {

    DIRECT("direct", "direct or one-on-one chat"),
    GROUP("group", "group chat"),
    CHANNEL("channel", "channel"),
    THREAD("thread", "thread or sub-discussion");

    private final String name;
    private final String description;

    ConversationType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
}