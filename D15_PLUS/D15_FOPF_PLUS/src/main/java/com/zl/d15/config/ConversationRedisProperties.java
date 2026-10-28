package com.zl.d15.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 角色多轮会话的 Redis 存储配置（前缀 {@code app.conversation.redis}）。
 *
 * <p>会话是短期高频状态，不写入 MySQL；每次读写都刷新 TTL，形成滑动过期，
 * 从总量上替代旧内存版的 500 会话硬上限（该差异在技术文档中显式说明）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.conversation.redis")
public class ConversationRedisProperties {

    /** 会话 Key 前缀，完整 Key = prefix + sessionId。 */
    private String keyPrefix = "d15f:conv:";

    /** 会话滑动 TTL（秒），末次活动后到期自动回收。 */
    private long ttlSeconds = 1800;
}
