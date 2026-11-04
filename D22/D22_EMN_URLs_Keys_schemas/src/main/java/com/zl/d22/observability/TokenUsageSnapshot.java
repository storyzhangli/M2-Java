package com.zl.d22.observability;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

/**
 * Token 用量快照（Token Usage Snapshot）。
 *
 * <p>这是不可变的归一化结果，只承载 Provider 真实返回的数值；缺失字段保留 null，
 * 并通过 {@link UsageStatus} 与 {@link UsageSource} 表达缺口，绝不估算、绝不填 0。</p>
 *
 * <p><b>为什么优先读原生 usage：</b>Spring AI 的可移植 {@link Usage} 有两个已知陷阱——
 * 其默认 {@code getTotalTokens()} 会把 null 的 prompt/completion 当 0 相加，
 * 而 {@code DefaultUsage} 还会把 null 字段强制成 0，无法区分“缺失”与“真实 0”。
 * DeepSeek 的原生 {@link DeepSeekApi.Usage} 是保留 null 的 record，因此本类优先通过
 * {@link Usage#getNativeUsage()} 读取原生对象获取真实判空；其它 Provider 再回退到可移植 getter。</p>
 */
public record TokenUsageSnapshot(UsageStatus status,
                                  UsageSource source,
                                  Integer promptTokens,
                                  Integer completionTokens,
                                  Integer totalTokens) {

    /**
     * 从 Spring AI usage 归一化；null 或 EmptyUsage 表示 Provider 未返回任何 usage。
     */
    public static TokenUsageSnapshot from(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return notReturned();
        }
        // 优先走 DeepSeek 原生 record（保留真实 null），避免被 DefaultUsage 强制成 0
        if (usage.getNativeUsage() instanceof DeepSeekApi.Usage nativeUsage) {
            return build(nativeUsage.promptTokens(),
                    nativeUsage.completionTokens(),
                    nativeUsage.totalTokens());
        }
        // 通用回退：直接信任可移植 getter；若该实现把缺失强制为 0，属抽象层已知限制
        return build(usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }

    /** 明确“未返回 usage”的快照。 */
    public static TokenUsageSnapshot notReturned() {
        return new TokenUsageSnapshot(UsageStatus.NOT_RETURNED, UsageSource.NONE, null, null, null);
    }

    /** 明确“该链路不支持 usage”的快照（如纯离线 mock 链路）。 */
    public static TokenUsageSnapshot notSupported() {
        return new TokenUsageSnapshot(UsageStatus.NOT_SUPPORTED, UsageSource.NONE, null, null, null);
    }

    /**
     * 核心判空与 total 推导。
     *
     * <p>total 仅在 prompt 与 completion 都非空、且 Provider 未给 total 时才本地相加并标 DERIVED；
     * 任一缺失则保留 Provider total 或 null，绝不反推。</p>
     */
    private static TokenUsageSnapshot build(Integer prompt, Integer completion, Integer providerTotal) {
        if (prompt == null && completion == null && providerTotal == null) {
            return notReturned();
        }

        Integer total = providerTotal;
        UsageSource source = UsageSource.PROVIDER_METADATA;
        if (providerTotal == null && prompt != null && completion != null) {
            total = prompt + completion;
            source = UsageSource.DERIVED;
        }

        UsageStatus status = (prompt != null && completion != null)
                ? UsageStatus.AVAILABLE
                : UsageStatus.PARTIAL;

        return new TokenUsageSnapshot(status, source, prompt, completion, total);
    }
}
