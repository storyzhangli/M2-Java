package com.zl.d22.adapter.deepseek;

import com.zl.d22.adapter.NativeUsage;
import com.zl.d22.adapter.NativeUsageReader;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 的 {@link NativeUsageReader} 实现。
 *
 * <p>DeepSeek 原生 {@link DeepSeekApi.Usage} 是保留 {@code null} 的 record，
 * 能区分“字段缺失”与“真实 0”；本类在适配边界内识别并读取它，
 * 观测层因此不再引用 {@code DeepSeekApi}。仅在 {@code app.ai.mode=deepseek} 时装配。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "deepseek")
public class DeepSeekNativeUsageReader implements NativeUsageReader {

    @Override
    public boolean supports(Usage usage) {
        return usage != null && usage.getNativeUsage() instanceof DeepSeekApi.Usage;
    }

    @Override
    public NativeUsage read(Usage usage) {
        DeepSeekApi.Usage nativeUsage = (DeepSeekApi.Usage) usage.getNativeUsage();
        return new NativeUsage(nativeUsage.promptTokens(),
                nativeUsage.completionTokens(),
                nativeUsage.totalTokens());
    }
}
