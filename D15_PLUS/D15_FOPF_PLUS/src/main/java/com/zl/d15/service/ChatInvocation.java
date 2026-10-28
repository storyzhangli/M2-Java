package com.zl.d15.service;

import java.util.List;

/**
 * 带角色系统提示词与多轮历史的模型调用入参。
 *
 * <p>旧接口 {@code chat(String)} / {@code streamChat(String)} 保留给无角色链路；
 * 角色对话链路通过本对象把“系统规则、历史上下文、当前用户消息”三段显式分开传递，
 * 用户消息始终作为独立用户消息存在，不可能被拼接进系统提示词位置而覆盖角色规则。</p>
 *
 * @param systemPrompt 已完成变量渲染的系统角色提示词（来自启用中的 Prompt 模板）
 * @param userMessage  当前轮次用户消息（原文，不做字符串拼接到系统提示词）
 * @param history      此前轮次的历史消息，按时间顺序排列
 */
public record ChatInvocation(String systemPrompt, String userMessage, List<Turn> history) {

    /**
     * 历史消息轮次。
     *
     * @param role    消息角色，仅允许 {@code user} / {@code assistant}
     * @param content 消息原文
     */
    public record Turn(String role, String content) {
    }
}
