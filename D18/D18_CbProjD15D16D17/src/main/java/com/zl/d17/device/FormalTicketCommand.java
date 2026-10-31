package com.zl.d17.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 正式工单写入命令（D18）。
 *
 * <p>与候选 DTO 区分：只有经过人工确认、需要真正建单/派单时才会构造该命令。
 * 本阶段不创建该命令，也不调用 {@link FormalTicketPort}。</p>
 *
 * @param deviceModel    设备型号
 * @param faultCode      故障码
 * @param symptomSummary 故障现象摘要
 * @param ticketCategory 工单分类（字符串，便于正式系统映射）
 * @param ownerId        工单归属人
 */
public record FormalTicketCommand(
        @NotBlank String deviceModel,
        String faultCode,
        @NotBlank @Size(max = 500) String symptomSummary,
        @NotBlank String ticketCategory,
        @NotBlank String ownerId
) {
}
