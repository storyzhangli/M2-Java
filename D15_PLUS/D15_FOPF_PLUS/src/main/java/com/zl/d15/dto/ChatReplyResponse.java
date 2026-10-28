package com.zl.d15.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 聊天接口响应数据体。
 *
 * <p>通过 {@link #mocked} 与 {@link #mode} 明确区分离线模拟结果与 DeepSeek 真实结果。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatReplyResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际处理模式：mock 或 deepseek。 */
    private String mode;

    /** 模型标识：模拟模式为 offline-mock；真实模式为实际配置的 DeepSeek 模型名。 */
    private String model;

    /** 是否为模拟结果（true 表示未发起真实模型调用）。 */
    private Boolean mocked;

    /** 回复文本内容。 */
    private String content;
}
