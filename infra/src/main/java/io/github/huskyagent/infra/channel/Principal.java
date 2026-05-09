package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

/**
 * 已认证主体 — 表示"谁在使用 Husky"。
 *
 * <p>不同渠道产生不同 Principal：</p>
 * <ul>
 *   <li>TUI: local:{profile}</li>
 *   <li>HTTP chatbot: api-key 映射的租户 + 调用方 user id</li>
 *   <li>飞书: bot instance + sender open_id</li>
 *   <li>Telegram: bot + sender chat id</li>
 * </ul>
 */
@Value
@Builder
public class Principal {

    /** 主体唯一标识，如 "local:default" / "tenant:acme:user:u123" / "feishu:bot1:ou_xxx" */
    String id;

    /** 主体显示名，可选 */
    String displayName;

    /** 来源渠道 */
    ChannelType channelType;
}