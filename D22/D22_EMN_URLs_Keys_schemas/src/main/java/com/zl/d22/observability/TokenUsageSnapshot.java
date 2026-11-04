package com.zl.d22.observability;

import com.zl.d22.adapter.NativeUsage;
import com.zl.d22.adapter.NativeUsageReader;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

/**
 * Token 用量快照（Token Usage Snapshot）。
 *
 * <p>这是不可变的归一化结果，只承载供应商真实返回的数值；缺失字段保留 null，
 * 并通过 {@link UsageStatus} 与 {@link UsageSource} 表达缺口，绝不估算、绝不填 0。</p>
 *
 * <p><b>为什么优先读原生 usage：</b>Spring AI 的可移植 {@link Usage} 有两个已知陷阱——
 * 其默认 {@code getTotalTokens()} 会把 null 的 prompt/completion 当 0 相加，
 * 而 {@code DefaultUsage} 还会把 null 字段强制成 0，无法区分“缺失”与“真实 0”。
 * 供应商的原生 usage 通常是保留 null 的 record。D22 起本类不再直接引用任何供应商类型，
 * 而是通过中立的 {@link NativeUsageReader} SPI 在适配边界内读取原生对象获取真实判空；
 * 没有匹配 reader 时再回退到可移植 getter。</p>
 */
public record TokenUsageSnapshot(UsageStatus status,
                                  UsageSource source,
                                  Integer promptTokens,
                                  Integer completionTokens,
                                  Integer totalTokens) {

    /**
     * 带原生用量读取器的归一化入口。
     *
     * <p>null 或 EmptyUsage 表示供应商未返回任何 usage；否则先找 {@code supports} 的 reader
     * 读取保留真实 null 的原生对象，没有匹配 reader 时回退到可移植 getter。</p>
     */
    public static TokenUsageSnapshot from(Usage usage, List<NativeUsageReader> readers) {
        if (usage == null || usage instanceof EmptyUsage) {
            return notReturned();
        }
        if (readers != null) {
            for (NativeUsageReader reader : readers) {
                if (reader.supports(usage)) {
                    NativeUsage nativeUsage = reader.read(usage);
                    return build(nativeUsage.promptTokens(),
                            nativeUsage.completionTokens(),
                            nativeUsage.totalTokens());
                }
            }
        }
        // 通用回退：直接信任可移植 getter；若该实现把缺失强制为 0，属抽象层已知限制
        return build(usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }

    /**
     * 不携带原生读取器的归一化入口（仅走可移植 getter 路径）。
     */
    public static TokenUsageSnapshot from(Usage usage) {
        return from(usage, List.of());
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
     * <p>total 仅在 prompt 与 completion 都非空、且供应商未给 total 时才本地相加并标 DERIVED；
     * 任一缺失则保留供应商 total 或 null，绝不反推。</p>
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
