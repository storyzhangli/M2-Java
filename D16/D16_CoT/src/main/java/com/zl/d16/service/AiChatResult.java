package com.zl.d16.service;

/**
 * 一次模型调用的结构化结果（D16 新增），把最终答案与模型原始推理内容显式分离。
 *
 * @param mode             实际处理模式：mock / deepseek
 * @param model            实际生效的模型标识（思考模式开启时可能是 reasoner 模型名）
 * @param mocked           是否为离线模拟结果
 * @param thinkingEnabled  本次请求是否启用思考模式
 * @param content          最终答案文本（对应模型 content），唯一允许入会话记忆的文本
 * @param reasoningContent 模型原始推理全文（对应 reasoning_content），可能为 null；
 *                         调用方仅在受控展示开关打开时才可对外返回，禁止写入普通日志和会话历史
 */
public record AiChatResult(String mode,
                           String model,
                           boolean mocked,
                           boolean thinkingEnabled,
                           String content,
                           String reasoningContent) {
}
