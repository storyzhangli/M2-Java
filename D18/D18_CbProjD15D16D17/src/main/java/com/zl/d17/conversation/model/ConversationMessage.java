package com.zl.d17.conversation.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话中的一条对话消息。
 *
 * @param role      消息角色：user（用户）或 assistant（模型回复）
 * @param content   消息原文
 * @param createdAt 落库时间
 */
public record ConversationMessage(
        String role,
        String content,
        LocalDateTime createdAt
) implements Serializable {

    /** 角色对话调用入参中使用的精简历史轮次角色值。 */
    public static final String ROLE_USER = "user";

    /** 角色对话调用入参中使用的精简历史轮次角色值。 */
    public static final String ROLE_ASSISTANT = "assistant";
}
