package com.zl.d17.springai.d16;

import com.zl.d17.dto.ChatRequest;
import com.zl.d17.service.ReasoningEffort;
import com.zl.d17.service.ThinkingSettings;
import com.zl.d17.service.ThinkingSettingsResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 思考设置服务端白名单裁决的纯单元测试，不启动 Spring 容器。
 */
class ThinkingSettingsResolverTest {

    private final ThinkingSettingsResolver resolver = new ThinkingSettingsResolver();

    private ChatRequest request() {
        return new ChatRequest();
    }

    @Test
    void nullFieldsShouldResolveToLegacyDefault() {
        ThinkingSettings settings = resolver.resolve(request());
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.showReasoning()).isFalse();
        assertThat(settings.effort()).isNull();
    }

    @Test
    void enabledWithoutShowFlagShouldKeepReasoningHidden() {
        ChatRequest req = request();
        req.setThinkingEnabled(true);
        ThinkingSettings settings = resolver.resolve(req);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.showReasoning()).isFalse();
    }

    @Test
    void showReasoningIsForcedFalseWhenThinkingDisabled() {
        ChatRequest req = request();
        req.setShowReasoning(true);
        ThinkingSettings settings = resolver.resolve(req);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.showReasoning()).isFalse();
    }

    @Test
    void legalEffortEnumShouldParseCaseInsensitive() {
        // 白名单枚举本身的解析能力（小写也能识别）
        assertThat(ReasoningEffort.parseWhitelisted("high")).isEqualTo(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.parseWhitelisted(" max ")).isEqualTo(ReasoningEffort.MAX);
    }

    @Test
    void illegalEffortValueShouldBeRejectedWith4001Exception() {
        ChatRequest req = request();
        req.setThinkingEnabled(true);
        req.setReasoningEffort("ultra");
        assertThatThrownBy(() -> resolver.resolve(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOW / HIGH / MAX");
    }

    @Test
    void effortWithoutThinkingEnabledShouldBeRejected() {
        ChatRequest req = request();
        req.setReasoningEffort("HIGH");
        assertThatThrownBy(() -> resolver.resolve(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请先显式启用思考模式");
    }

    @Test
    void explicitEffortMustBeRejectedBecauseClient118CannotTransmitIt() {
        // 关键可信约束：1.1.8 请求体无 reasoning_effort 字段，显式入参必须报错而非静默吞掉
        assertThat(ThinkingSettingsResolver.REASONING_EFFORT_TRANSMITTABLE).isFalse();
        ChatRequest req = request();
        req.setThinkingEnabled(true);
        req.setReasoningEffort("HIGH");
        assertThatThrownBy(() -> resolver.resolve(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持下发");
    }
}
