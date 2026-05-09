package io.github.huskyagent.infra.channel;

/**
 * 对话类型 — 区分私聊/群聊/频道/话题，影响 session 路由规则。
 */
public enum ConversationType {

    DIRECT("direct", "私聊/一对一"),
    GROUP("group", "群聊"),
    CHANNEL("channel", "频道"),
    THREAD("thread", "话题/子讨论");

    private final String name;
    private final String description;

    ConversationType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
}