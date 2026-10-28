package com.zl.d15.service;

import com.zl.d15.dto.ChatReplyResponse;
import reactor.core.publisher.Flux;

/**
 * AI 聊天服务抽象。
 *
 * <p>同一接口存在两个按 {@code app.ai.mode} 条件装配的实现：</p>
 * <ul>
 *     <li>{@link MockAiChatService}：mock 模式（默认），离线确定性返回，不访问外网；</li>
 *     <li>{@link DeepSeekAiChatService}：deepseek 模式，基于 Spring AI {@code ChatModel}
 *     调用 DeepSeek。</li>
 * </ul>
 *
 * <p>说明：本应用运行在 Servlet/Tomcat + Spring MVC 技术栈上，{@link Flux} 仅作为 Spring AI
 * 原生的流式数据管道使用，由 Controller 桥接到 MVC 的 {@code SseEmitter}，并不会切换到
 * WebFlux 反应式容器。</p>
 */
public interface AiChatService {

    /**
     * 发起一次单轮同步聊天。
     *
     * @param message 已通过 Controller 校验的非空白用户消息
     * @return 聊天响应数据
     */
    ChatReplyResponse chat(String message);

    /**
     * 发起一次流式聊天，逐段返回模型生成的文本增量。
     *
     * <p>每个发射项是一段文本增量（不保证对应一个 token、单词或整句），调用方需按到达顺序追加；
     * 正常结束时流 {@code onComplete}，模型/网络异常时 {@code onError}，由上层转换为 SSE 事件。</p>
     *
     * @param message 已通过 Controller 校验的非空白用户消息
     * @return 文本增量流
     */
    Flux<String> streamChat(String message);
}
