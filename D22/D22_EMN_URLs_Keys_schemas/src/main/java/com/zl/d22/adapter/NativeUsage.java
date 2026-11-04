package com.zl.d22.adapter;

/**
 * 供应商中立的原生用量载体（Native Usage）。
 *
 * <p>各字段保留 {@code null} 以表达“供应商未返回该字段”，与“真实 0”严格区分，
 * 绝不估算、绝不填 0。由 {@link NativeUsageReader} 从供应商原生 usage 对象映射得到。</p>
 *
 * @param promptTokens     提示词 Token 数，可空
 * @param completionTokens 补全 Token 数，可空
 * @param totalTokens      总 Token 数，可空
 */
public record NativeUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
