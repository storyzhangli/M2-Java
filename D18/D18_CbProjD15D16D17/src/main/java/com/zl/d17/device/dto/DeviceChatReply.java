package com.zl.d17.device.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 设备场景对话响应（D18）。
 *
 * <p>清晰区分答复、澄清职责、缺失信息与候选提取，避免把候选误读为正式工单。</p>
 *
 * @param sessionId            服务端会话 ID
 * @param scene                场景标识
 * @param templateId           实际生效模板 ID
 * @param templateVersion      实际生效模板版本
 * @param mode                 处理模式
 * @param model                模型标识
 * @param mocked               是否离线模拟
 * @param answer               最终答复文本
 * @param clarificationRequired 是否需要澄清
 * @param missingInformation   缺失信息清单
 * @param clarificationQuestion 单条澄清问题
 * @param uncertaintyNote      不确定性与来源说明
 * @param candidateExtraction  故障工单候选（非正式单）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceChatReply(
        String sessionId,
        String scene,
        String templateId,
        Integer templateVersion,
        String mode,
        String model,
        Boolean mocked,
        String answer,
        boolean clarificationRequired,
        List<String> missingInformation,
        String clarificationQuestion,
        String uncertaintyNote,
        FaultTicketCandidate candidateExtraction
) {
}
