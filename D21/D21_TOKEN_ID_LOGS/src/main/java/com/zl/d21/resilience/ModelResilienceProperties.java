package com.zl.d21.resilience;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 模型调用韧性配置（前缀 {@code app.ai.resilience}）。
 *
 * <p>把模型 HTTP 客户端的连接/读取超时、单次总时限、流式空闲超时、有限重试次数与退避
 * 全部外部化，与 Spring AI 官方 {@code spring.ai.retry.*}（本次置为不重试）以及面向前端的
 * SSE 容器超时 {@code app.ai.stream-timeout-ms} 分层管理。</p>
 *
 * <p>所有取值在 {@link #validate()} 中做范围校验，非法配置在启动阶段快速失败，
 * 避免运行期出现无限重试或负超时。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.resilience")
public class ModelResilienceProperties {

    /** 建连（含 TLS 握手）超时，默认 3 秒。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读取超时（两次数据之间的等待上限），默认 30 秒。 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 一次逻辑调用（含全部重试）的总墙钟上限，默认 60 秒。 */
    private Duration totalDeadline = Duration.ofSeconds(60);

    /** 流式两个分片之间的空闲超时，默认 30 秒。 */
    private Duration streamIdleTimeout = Duration.ofSeconds(30);

    /** 总尝试次数（含首次），默认 2，即最多重试 1 次。 */
    private int maxAttempts = 2;

    /** 读取超时是否允许重试：默认关闭（请求已发送、结果不确定）。 */
    private boolean retryOnReadTimeout = false;

    /** 退避配置。 */
    private Backoff backoff = new Backoff();

    /**
     * 退避参数。
     */
    @Data
    public static class Backoff {

        /** 首次退避基数，默认 200 毫秒。 */
        private Duration initial = Duration.ofMillis(200);

        /** 退避增长倍率，默认 2。 */
        private double multiplier = 2.0;

        /** 单次退避上限，默认 5 秒。 */
        private Duration maxInterval = Duration.ofSeconds(5);

        /** 抖动因子（0~1），实际等待在基准值附近按该比例随机抖动，默认 0.2。 */
        private double jitterFactor = 0.2;
    }

    /**
     * 启动期范围校验。
     */
    @PostConstruct
    void validate() {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("app.ai.resilience.max-attempts 必须在 1~5 之间，当前=" + maxAttempts);
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requirePositive(totalDeadline, "total-deadline");
        requirePositive(streamIdleTimeout, "stream-idle-timeout");
        requirePositive(backoff.initial, "backoff.initial");
        requirePositive(backoff.maxInterval, "backoff.max-interval");
        if (backoff.multiplier < 1.0) {
            throw new IllegalArgumentException("app.ai.resilience.backoff.multiplier 不能小于 1，当前=" + backoff.multiplier);
        }
        if (backoff.jitterFactor < 0.0 || backoff.jitterFactor > 1.0) {
            throw new IllegalArgumentException("app.ai.resilience.backoff.jitter-factor 必须在 0~1 之间，当前=" + backoff.jitterFactor);
        }
        if (totalDeadline.compareTo(connectTimeout) <= 0) {
            throw new IllegalArgumentException("app.ai.resilience.total-deadline 必须大于 connect-timeout");
        }
    }

    private void requirePositive(Duration value, String key) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.ai.resilience." + key + "必须为正的时长，当前=" + value);
        }
    }
}
