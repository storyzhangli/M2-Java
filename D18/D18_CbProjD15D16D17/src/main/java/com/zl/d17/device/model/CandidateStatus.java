package com.zl.d17.device.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 工单候选状态（D18）。
 *
 * <p>表示当前候选是否仍需澄清；该状态只描述候选成熟度，不代表正式工单状态。</p>
 */
public enum CandidateStatus {

    /** 关键信息齐备，可作为较完整候选提交人工确认。 */
    CANDIDATE,

    /** 仍存在缺失信息，需要继续澄清。 */
    NEEDS_CLARIFICATION;

    /**
     * 容错解析：无法识别的取值统一归 {@link #NEEDS_CLARIFICATION}，保守处理。
     */
    @JsonCreator
    public static CandidateStatus fromValue(String value) {
        if (value == null) {
            return NEEDS_CLARIFICATION;
        }
        return "CANDIDATE".equalsIgnoreCase(value.trim()) ? CANDIDATE : NEEDS_CLARIFICATION;
    }
}
