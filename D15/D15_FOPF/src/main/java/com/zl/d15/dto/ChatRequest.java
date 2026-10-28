package com.zl.d15.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 聊天接口请求体。
 */
@Data
public class ChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 注解层面的绝对长度上限，防止绕过业务可配置上限的超长请求。 */
    public static final int HARD_MAX_MESSAGE_LENGTH = 2000;

    /**
     * 用户提问内容。
     *
     * <p>{@code @NotBlank} 拒绝 null/空串/纯空白；{@code @Size} 提供硬性长度天花板，
     * 业务可配置上限（{@code app.ai.max-message-length}）在 Controller 中再次校验。</p>
     */
    @NotBlank(message = "message 不能为空或纯空白")
    @Size(max = HARD_MAX_MESSAGE_LENGTH, message = "message 长度不能超过 " + HARD_MAX_MESSAGE_LENGTH + " 个字符")
    private String message;
}
