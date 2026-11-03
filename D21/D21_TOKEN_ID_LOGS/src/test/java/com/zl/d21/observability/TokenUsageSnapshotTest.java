package com.zl.d21.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 用量快照测试：覆盖完整、部分、缺失、推导、显式零值与通用回退等场景。
 *
 * <p>真实路径用 DeepSeek 原生 record（保留 null）包装成 DefaultUsage，与生产
 * {@code DeepSeekChatModel.getDefaultUsage} 的构造方式一致；不启动 Spring 上下文、不触外网。</p>
 */
class TokenUsageSnapshotTest {

    /**
     * 构造与生产一致的 usage：原生 record 作为 nativeUsage 传入 DefaultUsage。
     * 注意原生 record 构造参数顺序为 (completion, prompt, total)。
     */
    private Usage deepseek(Integer prompt, Integer completion, Integer total) {
        DeepSeekApi.Usage nativeRecord = new DeepSeekApi.Usage(completion, prompt, total);
        return new DefaultUsage(prompt, completion, total, nativeRecord);
    }

    @Test
    @DisplayName("usage 为 null：状态 NOT_RETURNED，数值全 null，来源 NONE")
    void nullUsage() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(null);
        assertThat(snapshot.status()).isEqualTo(UsageStatus.NOT_RETURNED);
        assertThat(snapshot.source()).isEqualTo(UsageSource.NONE);
        assertThat(snapshot.promptTokens()).isNull();
        assertThat(snapshot.completionTokens()).isNull();
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("usage 为 EmptyUsage：状态 NOT_RETURNED，数值全 null")
    void emptyUsage() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(new EmptyUsage());
        assertThat(snapshot.status()).isEqualTo(UsageStatus.NOT_RETURNED);
        assertThat(snapshot.source()).isEqualTo(UsageSource.NONE);
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("原生字段全为 null：状态 NOT_RETURNED，不被 DefaultUsage 强制成 0")
    void allNullFields() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(null, null, null));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.NOT_RETURNED);
        assertThat(snapshot.promptTokens()).isNull();
        assertThat(snapshot.completionTokens()).isNull();
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("prompt、completion、total 均由 Provider 返回：AVAILABLE，PROVIDER_METADATA，原值保留")
    void fullProviderUsage() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(10, 20, 30));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.source()).isEqualTo(UsageSource.PROVIDER_METADATA);
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isEqualTo(20);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("prompt、completion 返回但无 total：AVAILABLE，total 推导为二者之和，来源 DERIVED")
    void derivedTotal() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(10, 20, null));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.source()).isEqualTo(UsageSource.DERIVED);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("仅 prompt 返回：PARTIAL，不推导 total，total 为 null")
    void onlyPrompt() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(10, null, null));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.source()).isEqualTo(UsageSource.PROVIDER_METADATA);
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isNull();
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("仅 completion 返回：PARTIAL，不推导 total")
    void onlyCompletion() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(null, 20, null));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.completionTokens()).isEqualTo(20);
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("仅 total 返回：PARTIAL，保留 Provider total，不反推 prompt/completion")
    void onlyTotal() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(null, null, 30));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
        assertThat(snapshot.promptTokens()).isNull();
        assertThat(snapshot.completionTokens()).isNull();
    }

    @Test
    @DisplayName("Provider 明确返回 0：0 被真实记录（不是缺失，也不是伪造）")
    void explicitZeroPreserved() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(deepseek(0, 0, 0));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.promptTokens()).isZero();
        assertThat(snapshot.completionTokens()).isZero();
        assertThat(snapshot.totalTokens()).isZero();
    }

    @Test
    @DisplayName("无 native 对象的通用 Provider：回退可移植 getter，完整 usage 正常归一化")
    void portableFallback() {
        Usage portable = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 7;
            }

            @Override
            public Integer getCompletionTokens() {
                return 9;
            }

            @Override
            public Integer getTotalTokens() {
                return null;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(portable);
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.source()).isEqualTo(UsageSource.DERIVED);
        assertThat(snapshot.totalTokens()).isEqualTo(16);
    }

    @Test
    @DisplayName("notSupported 快照：状态 NOT_SUPPORTED，来源 NONE，数值全 null")
    void notSupported() {
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.notSupported();
        assertThat(snapshot.status()).isEqualTo(UsageStatus.NOT_SUPPORTED);
        assertThat(snapshot.source()).isEqualTo(UsageSource.NONE);
        assertThat(snapshot.totalTokens()).isNull();
    }
}
