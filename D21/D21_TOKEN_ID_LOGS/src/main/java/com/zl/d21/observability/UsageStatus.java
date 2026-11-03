package com.zl.d21.observability;

/**
 * Token 用量的可用状态（Usage Status）。
 *
 * <p>该状态独立于具体数值存在：即使没有任何 token 数值，也必须给出明确状态，
 * 而不是用 0 或估算值掩盖“没有拿到数据”这一事实。各状态含义如下：</p>
 * <ul>
 *     <li>{@link #AVAILABLE}：Provider 返回了完整的 prompt 与 completion tokens
 *     （total 为 Provider 返回或按规则 DERIVED）；</li>
 *     <li>{@link #PARTIAL}：存在真实 usage 对象，但 prompt/completion/total 中一个或多个字段缺失；</li>
 *     <li>{@link #UNAVAILABLE}：因已知条件（如适配层显式声明不可用）导致本次无法取得 usage；</li>
 *     <li>{@link #NOT_RETURNED}：调用链存在，但 Provider 未返回 usage（null、EmptyUsage 或字段全空）；</li>
 *     <li>{@link #NOT_SUPPORTED}：当前链路不支持 usage（如离线 mock，没有 Provider 与模型调用）。</li>
 * </ul>
 */
public enum UsageStatus {

    /** prompt 与 completion tokens 均由 Provider 实际返回，usage 完整可用。 */
    AVAILABLE,

    /** 有真实 usage 数据，但部分字段缺失（逐项判空后仍有 null）。 */
    PARTIAL,

    /** 因明确的适配/运行条件，本次 usage 不可用。 */
    UNAVAILABLE,

    /** Provider 未返回 usage（无对象、空对象或字段全为 null）。 */
    NOT_RETURNED,

    /** 当前链路不支持 usage（无 Provider 调用，例如离线 mock）。 */
    NOT_SUPPORTED
}
