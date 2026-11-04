package com.zl.d22.observability;

import com.zl.d22.adapter.NativeUsage;
import com.zl.d22.adapter.NativeUsageReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 用量快照测试：覆盖完整、部分、缺失、推导、显式零值与通用回退等场景。
 *
 * <p>D22 起不再依赖任何供应商专有类型：原生路径用中立的 {@link NativeUsage} 载体与
 * 一个测试桩 {@link NativeUsageReader} 验证 reader 优先逻辑；供应商原生 record 的
 * 读取测试迁移到适配层测试包。不启动 Spring 上下文、不触外网。</p>
 */
class TokenUsageSnapshotTest {

    private final NativeUsageReader stubReader = new StubNativeUsageReader();

    /** 测试桩：识别以中立 {@link NativeUsage} 为原生对象的 usage 并原样返回。 */
    static class StubNativeUsageReader implements NativeUsageReader {
        @Override
        public boolean supports(Usage usage) {
            return usage != null && usage.getNativeUsage() instanceof NativeUsage;
        }

        @Override
        public NativeUsage read(Usage usage) {
            return (NativeUsage) usage.getNativeUsage();
        }
    }

    /** 携带中立原生载体的测试 usage。 */
    static final class StubUsage implements Usage {
        private final NativeUsage nativeUsage;

        StubUsage(NativeUsage nativeUsage) {
            this.nativeUsage = nativeUsage;
        }

        @Override
        public Integer getPromptTokens() {
            return nativeUsage.promptTokens();
        }

        @Override
        public Integer getCompletionTokens() {
            return nativeUsage.completionTokens();
        }

        @Override
        public Integer getTotalTokens() {
            return nativeUsage.totalTokens();
        }

        @Override
        public Object getNativeUsage() {
            return nativeUsage;
        }
    }

    private Usage nativeUsage(Integer prompt, Integer completion, Integer total) {
        return new StubUsage(new NativeUsage(prompt, completion, total));
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
    @DisplayName("原生字段全为 null：状态 NOT_RETURNED，不被可移植 getter 强制成 0")
    void allNullFields() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(null, null, null), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.NOT_RETURNED);
        assertThat(snapshot.promptTokens()).isNull();
        assertThat(snapshot.completionTokens()).isNull();
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("prompt、completion、total 均由 Provider 返回：AVAILABLE，PROVIDER_METADATA，原值保留")
    void fullProviderUsage() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(10, 20, 30), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.source()).isEqualTo(UsageSource.PROVIDER_METADATA);
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isEqualTo(20);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("prompt、completion 返回但无 total：AVAILABLE，total 推导为二者之和，来源 DERIVED")
    void derivedTotal() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(10, 20, null), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.source()).isEqualTo(UsageSource.DERIVED);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("仅 prompt 返回：PARTIAL，不推导 total，total 为 null")
    void onlyPrompt() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(10, null, null), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.source()).isEqualTo(UsageSource.PROVIDER_METADATA);
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isNull();
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("仅 completion 返回：PARTIAL，不推导 total")
    void onlyCompletion() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(null, 20, null), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.completionTokens()).isEqualTo(20);
        assertThat(snapshot.totalTokens()).isNull();
    }

    @Test
    @DisplayName("仅 total 返回：PARTIAL，保留 Provider total，不反推 prompt/completion")
    void onlyTotal() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(null, null, 30), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.PARTIAL);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
        assertThat(snapshot.promptTokens()).isNull();
        assertThat(snapshot.completionTokens()).isNull();
    }

    @Test
    @DisplayName("Provider 明确返回 0：0 被真实记录（不是缺失，也不是伪造）")
    void explicitZeroPreserved() {
        TokenUsageSnapshot snapshot =
                TokenUsageSnapshot.from(nativeUsage(0, 0, 0), List.of(stubReader));
        assertThat(snapshot.status()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(snapshot.promptTokens()).isZero();
        assertThat(snapshot.completionTokens()).isZero();
        assertThat(snapshot.totalTokens()).isZero();
    }

    @Test
    @DisplayName("无匹配 reader 的通用 Provider：回退可移植 getter，完整 usage 正常归一化")
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
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(portable, List.of());
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
