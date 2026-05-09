package io.github.huskyagent.infra.channel;

/**
 * 渠道类型 — 客户端/产品形态，决定身份来源和消息路由。
 *
 * <p>Transport（WS/HTTP）是纯传输协议，不承载业务语义；
 * Channel 决定"谁在访问、从哪个产品形态来"。</p>
 */
public enum ChannelType {

    TUI("tui", "本地 TUI 个人助理"),
    FEISHU("feishu", "飞书 bot"),
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