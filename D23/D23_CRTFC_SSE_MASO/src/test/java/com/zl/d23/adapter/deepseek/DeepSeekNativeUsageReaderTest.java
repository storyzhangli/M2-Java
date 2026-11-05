package com.zl.d23.adapter.deepseek;

import com.zl.d23.adapter.NativeUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 原生用量读取器测试：验证供应商原生 record 的识别与真实判空映射。
 * 不启动 Spring 上下文、不触外网。
 */
class DeepSeekNativeUsageReaderTest {

    private final DeepSeekNativeUsageReader reader = new DeepSeekNativeUsageReader();

    /** 原生 record 构造参数顺序为 (completion, prompt, total)。 */
    private Usage wrapped(Integer prompt, Integer completion, Integer total) {
        DeepSeekApi.Usage nativeRecord = new DeepSeekApi.Usage(completion, prompt, total);
        return new DefaultUsage(prompt, completion, total, nativeRecord);
    }

    @Test
    @DisplayName("supports：原生对象为 DeepSeekApi.Usage 时返回 true")
    void supportsDeepSeekNative() {
        assertThat(reader.supports(wrapped(1, 2, 3))).isTrue();
    }

    @Test
    @DisplayName("supports：无原生对象或 null usage 时返回 false")
    void doesNotSupportPortableOrNull() {
        Usage portable = new DefaultUsage(1, 2, 3);
        assertThat(reader.supports(portable)).isFalse();
        assertThat(reader.supports(null)).isFalse();
    }

    @Test
    @DisplayName("read：完整字段原样映射，保留真实值")
    void readFullValues() {
        NativeUsage usage = reader.read(wrapped(10, 20, 30));

        assertThat(usage.promptTokens()).isEqualTo(10);
        assertThat(usage.completionTokens()).isEqualTo(20);
        assertThat(usage.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("read：原生缺失字段保留 null，不被强制成 0")
    void readPreservesNulls() {
        NativeUsage usage = reader.read(wrapped(null, 20, null));

        assertThat(usage.promptTokens()).isNull();
        assertThat(usage.completionTokens()).isEqualTo(20);
        assertThat(usage.totalTokens()).isNull();
    }
}
