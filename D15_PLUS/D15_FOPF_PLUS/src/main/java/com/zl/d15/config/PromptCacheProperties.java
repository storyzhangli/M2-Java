package com.zl.d15.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prompt 模板 Redis 缓存配置（前缀 {@code app.prompt.cache}）。
 *
 * <p>缓存只承担读加速，MySQL 始终是权威存储；所有 Key 带应用与业务域前缀，
 * 避免与同一 Redis 实例中的其他业务串用。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.prompt.cache")
public class PromptCacheProperties {

    /** 缓存总开关；关闭后所有读直接回源 MySQL，写也不再执行失效动作。 */
    private boolean enabled = true;

    /** 模板详情 Key 前缀，完整 Key = prefix + templateId。 */
    private String keyPrefix = "d15f:prompt:tpl:";

    /** 空值标记 Key 后缀，短 TTL 用于抑制对不存在模板的反复回源（缓存穿透保护）。 */
    private String nullKeySuffix = ":null";

    /** 正常模板缓存 TTL（秒），命中时滑动续期。 */
    private long ttlSeconds = 1800;

    /** 空值标记 TTL（秒）。 */
    private long nullTtlSeconds = 60;
}
