package com.zl.d15.dto;

import java.io.Serializable;

/**
 * 角色对话同步响应体。
 *
 * <p>在通用回复字段之外携带会话与模板溯源信息，调用方凭 sessionId 延续多轮对话，
 * 凭 templateId/templateVersion 定位本次实际生效的模板版本。</p>
 */
public record RoleChatReplyResponse(
        String sessionId,
        String scene,
        String templateId,
        int templateVersion,
        String mode,
        String model,
        Boolean mocked,
        String content
) implements Serializable {

    /**
     * 由通用聊天响应与会话上下文组装角色响应。
     */
    public static RoleChatReplyResponse from(String sessionId, String scene,
                                             String templateId, int templateVersion,
                                             ChatReplyResponse reply) {
        return new RoleChatReplyResponse(
                sessionId,
                scene,
                templateId,
                templateVersion,
                reply.getMode(),
                reply.getModel(),
                reply.getMocked(),
                reply.getContent()
        );
    }
}
