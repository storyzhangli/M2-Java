package com.zl.d17.device.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 模型原始结构化输出的映射对象（D18）。
 *
 * <p>采用宽松策略：{@code ignoreUnknown=true} 忽略模型多输出的字段，避免因额外字段直接失败；
 * 枚举字段在此先用字符串接收，再由 {@code StructuredCandidateParser} 做容错转换与校验。</p>
 *
 * @param symptomSummary     故障现象摘要
 * @param ticketCategory     工单分类（原始字符串）
 * @param deviceModel        设备型号
 * @param faultCode          故障码
 * @param observedSymptoms   可观察症状
 * @param missingInformation 缺失信息
 * @param uncertaintyNote    不确定性说明
 * @param candidateStatus    候选状态（原始字符串）
 * @param sourceType         信息来源（原始字符串）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawCandidatePayload(
        String symptomSummary,
        String ticketCategory,
        String deviceModel,
        String faultCode,
        List<String> observedSymptoms,
        List<String> missingInformation,
        String uncertaintyNote,
        String candidateStatus,
        String sourceType
) {
}
