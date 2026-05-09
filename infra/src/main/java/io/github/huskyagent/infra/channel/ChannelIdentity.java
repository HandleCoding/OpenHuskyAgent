package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

/**
 * 渠道身份 — 消息来源与会话路由语义。
 *
 * <p>不同 ChannelAdapter 把 inbound payload 归一化成 ChannelIdentity，
 * 然后交给 SessionResolver 决定 session routing。</p>
 */
@Value
@Builder
public class ChannelIdentity {

    /** 渠道类型 */
    ChannelType channelType;

    /** 对话类型 */
    ConversationType conversationType;

    /** 平台账号 ID（飞书 bot app_id / Telegram bot username），本地渠道为 null */
    String platformAccountId;

    /** 平台聊天 ID（飞书 chat_id / Telegram chat_id），私聊时等于 senderId */
    String chatId;

    /** 话题/线程 ID（飞书 thread_id / Telegram topic id），非话题型对话为 null */
    String threadId;

    /** 发送者 ID（飞书 open_id / Telegram sender user_id），TUI 为 profile id */
    String senderId;

    /** WS/HTTP 连接 ID，由传输层分配 */
    String connectionId;
}