package com.zl.d22.observability;

/**
 * Token 用量数值的来源（Usage Source）。
 *
 * <p>该枚举与 {@link UsageStatus} 配合，明确每个数值是“Provider 真实返回”还是“本地按规则推导”，
 * 从来源上区分真实数据与推导数据，防止把估算值伪装成 Provider 用量。</p>
 */
public enum UsageSource {

    /** 数值直接来自 Provider 在 ChatResponse metadata 中返回的 usage。 */
    PROVIDER_METADATA,

    /**
     * 本地按明确规则推导：仅当 Provider 同时返回 prompt 与 completion tokens 时，
     * 由二者相加得到 total tokens，且只用于 total 这一个字段。
     */
    DERIVED,

    /** 没有任何可用来源（无 Provider 调用或 Provider 未返回 usage）。 */
    NONE
}
