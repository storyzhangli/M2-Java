package com.zl.d01.springai.service;

import com.zl.d01.springai.dto.ChatReplyResponse;

/**
 * AI 聊天服务抽象。
 *
 * <p>同一接口存在两个按 {@code app.ai.mode} 条件装配的实现：</p>
 * <ul>
 *     <li>{@link MockAiChatService}：mock 模式（默认），离线确定性返回，不访问外网；</li>
 *     <li>{@link DeepSeekAiChatService}：deepseek 模式，基于 Spring AI {@code ChatModel}
 *     调用 DeepSeek。</li>
 * </ul>
 */
public interface AiChatService {

    /**
     * 发起一次单轮同步聊天。
     *
     * @param message 已通过 Controller 校验的非空白用户消息
     * @return 聊天响应数据
     */
    ChatReplyResponse chat(String message);
}
