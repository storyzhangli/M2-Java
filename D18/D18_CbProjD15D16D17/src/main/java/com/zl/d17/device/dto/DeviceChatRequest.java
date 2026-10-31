package com.zl.d17.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 设备场景对话请求（D18）。
 *
 * @param scene     设备场景，仅允许 device_customer_service / technical_engineer
 * @param sessionId 会话 ID，首次对话为空由服务端生成；多轮对话回传
 * @param message   用户消息正文
 * @param variables 模板变量（可选，仅允许模板已声明的变量）
 */
public record DeviceChatRequest(
        @NotBlank(message = "场景不能为空")
        String scene,
        @Size(max = 64, message = "会话标识长度非法")
        String sessionId,
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 4000, message = "消息内容长度不能超过4000个字符")
        String message,
        Map<String, Object> variables
) {
}
