package com.zl.d17.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 角色对话响应结构：在普通聊天响应之外回传会话与模板溯源信息。
 *
 * <p>D16 追加思考模式开关、受控推理内容与引用来源字段；类级 NON_NULL 保证旧链路帧不出现 null 扩展字段。</p>
 *
 * @param sessionId       服务端生成并持久化的会话 ID
 * @param scene           场景标识
 * @param templateId      实际生效的模板 ID
 * @param templateVersion 实际生效的模板版本号
 * @param mode            实际处理模式
 * @param model           模型标识
 * @param mocked          是否离线模拟
 * @param content         最终答案文本
 * @param thinkingEnabled 是否启用思考模式（旧链路为 null 隐藏）
 * @param reasoningContent 受控展示的原始推理内容，默认 null
 * @param citations       经后端校验的引用来源，无引用时为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleChatReplyResponse(String sessionId,
                                    String scene,
                                    String templateId,
                                    Integer templateVersion,
                                    String mode,
                                    String model,
                                    Boolean mocked,
                                    String content,
                                    Boolean thinkingEnabled,
                                    String reasoningContent,
                                    List<CitationDto> citations) {

    /**
     * D16 全量工厂方法：由普通响应、会话溯源与思考/引用字段组装。
     */
    public static RoleChatReplyResponse from(String sessionId, String scene, String templateId,
                                             Integer templateVersion, ChatReplyResponse reply,
                                             String reasoningContent, List<CitationDto> citations) {
        return new RoleChatReplyResponse(sessionId, scene, templateId, templateVersion,
                reply.getMode(), reply.getModel(), reply.getMocked(), reply.getContent(),
                reply.getThinkingEnabled(), reasoningContent, citations);
    }

    /**
     * 旧工厂方法（D16 之前调用方兼容入口）：思考字段为 null、引用为空列表。
     */
    public static RoleChatReplyResponse from(String sessionId, String scene, String templateId,
                                             Integer templateVersion, ChatReplyResponse reply) {
        return from(sessionId, scene, templateId, templateVersion, reply, null, List.of());
    }
}
