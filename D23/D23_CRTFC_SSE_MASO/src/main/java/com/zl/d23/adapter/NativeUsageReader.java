package com.zl.d23.adapter;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 供应商中立的原生用量读取端口（Native Usage Reader）。
 *
 * <p>Spring AI 的可移植 {@link Usage} 存在把 {@code null} 强制为 0 的已知陷阱，
 * 而部分供应商的原生 usage 是保留 {@code null} 的 record。本接口让供应商在适配边界内
 * 识别自己的原生对象并读取真实判空，观测层因此无需引用任何供应商专有类型。</p>
 */
public interface NativeUsageReader {

    /**
     * 是否能读取该 usage 的原生对象。
     *
     * @param usage Spring AI 可移植 usage
     * @return 当原生对象是本供应商类型时返回 true
     */
    boolean supports(Usage usage);

    /**
     * 从原生对象映射为可移植的用量载体。
     *
     * @param usage 已通过 {@link #supports(Usage)} 确认的 usage
     * @return 保留真实 null 的用量载体
     */
    NativeUsage read(Usage usage);
}
