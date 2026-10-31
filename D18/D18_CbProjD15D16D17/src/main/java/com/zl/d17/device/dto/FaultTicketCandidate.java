package com.zl.d17.device.dto;

import com.zl.d17.device.model.CandidateStatus;
import com.zl.d17.device.model.InformationSource;
import com.zl.d17.device.model.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 故障工单候选 DTO（D18，record）。
 *
 * <p><b>仅为候选结果</b>：不写正式业务表、不建单、不派单；由人工确认后才可能进入正式工单流程。
 * 字段约束通过 Bean Validation 校验，超长或缺失会被明确拦截，不静默截断。</p>
 *
 * @param symptomSummary     简明故障现象摘要（必填，最长 500 字符）
 * @param ticketCategory     工单分类候选，无法确认时为 UNKNOWN
 * @param deviceModel        设备型号，未提供为 null
 * @param faultCode          故障码，未提供为 null
 * @param observedSymptoms   可观察症状列表
 * @param missingInformation 仍缺失的信息清单
 * @param uncertaintyNote    不确定性与来源说明
 * @param candidateStatus    候选状态
 * @param sourceType         主要信息来源类型
 */
public record FaultTicketCandidate(
        @NotBlank(message = "故障现象摘要不能为空")
        @Size(max = 500, message = "故障现象摘要长度不能超过500个字符")
        String symptomSummary,
        TicketCategory ticketCategory,
        @Size(max = 128, message = "设备型号长度不能超过128个字符")
        String deviceModel,
        @Size(max = 64, message = "故障码长度不能超过64个字符")
        String faultCode,
        List<String> observedSymptoms,
        List<String> missingInformation,
        @Size(max = 1000, message = "不确定性说明长度不能超过1000个字符")
        String uncertaintyNote,
        CandidateStatus candidateStatus,
        InformationSource sourceType
) {
}
