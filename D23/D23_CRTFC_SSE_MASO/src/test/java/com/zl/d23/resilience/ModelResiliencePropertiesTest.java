package com.zl.d23.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 韧性配置范围校验测试（T11）。
 *
 * <p>直接调用 {@link ModelResilienceProperties} 的校验方法，验证非法配置在启动阶段
 * 快速失败，而不是拖到运行期才暴露。</p>
 */
class ModelResiliencePropertiesTest {

    @Test
    @DisplayName("默认配置通过校验")
    void defaultsAreValid() {
        new ModelResilienceProperties().validate();
    }

    @Test
    @DisplayName("T11 max-attempts 越界抛出异常")
    void invalidMaxAttempts() {
        ModelResilienceProperties zero = new ModelResilienceProperties();
        zero.setMaxAttempts(0);
        assertThatThrownBy(zero::validate).isInstanceOf(IllegalArgumentException.class);

        ModelResilienceProperties tooLarge = new ModelResilienceProperties();
        tooLarge.setMaxAttempts(6);
        assertThatThrownBy(tooLarge::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T11 抖动因子越界抛出异常")
    void invalidJitter() {
        ModelResilienceProperties properties = new ModelResilienceProperties();
        properties.getBackoff().setJitterFactor(1.5d);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T11 非正时长/退避倍率小于 1 抛出异常")
    void invalidDurationAndMultiplier() {
        ModelResilienceProperties zeroRead = new ModelResilienceProperties();
        zeroRead.setReadTimeout(Duration.ZERO);
        assertThatThrownBy(zeroRead::validate).isInstanceOf(IllegalArgumentException.class);

        ModelResilienceProperties lowMultiplier = new ModelResilienceProperties();
        lowMultiplier.getBackoff().setMultiplier(0.5d);
        assertThatThrownBy(lowMultiplier::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T11 总时限不大于建连超时抛出异常")
    void deadlineNotGreaterThanConnect() {
        ModelResilienceProperties properties = new ModelResilienceProperties();
        properties.setConnectTimeout(Duration.ofSeconds(10));
        properties.setTotalDeadline(Duration.ofSeconds(5));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("T11 默认值与任务书口径一致")
    void defaultsMatchBaseline() {
        ModelResilienceProperties properties = new ModelResilienceProperties();
        assertThat(properties.getMaxAttempts()).isEqualTo(2);
        assertThat(properties.isRetryOnReadTimeout()).isFalse();
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getTotalDeadline()).isEqualTo(Duration.ofSeconds(60));
    }
}
