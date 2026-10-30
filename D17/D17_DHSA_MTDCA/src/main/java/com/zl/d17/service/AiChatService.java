package com.zl.d17.service;

import com.zl.d17.dto.ChatReplyResponse;
import reactor.core.publisher.Flux;

import java.util.List;

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
     * 发起一次带系统角色提示词与多轮历史的同步聊天（D15 角色对话链路）。
     *
     * <p>默认实现退化为仅使用当前用户消息的旧行为，保证既有实现类与测试不强制改造；
     * 需要角色能力的实现类覆盖本方法，把系统提示词与历史组装为真实模型消息。</p>
     *
     * @param invocation 系统提示词、历史与当前用户消息的组合入参
     * @return 聊天响应数据
     */
    default ChatReplyResponse chat(ChatInvocation invocation) {
        return chat(invocation.userMessage());
    }

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

    /**
     * 发起一次带系统角色提示词与多轮历史的流式聊天（D15 角色对话链路）。
     *
     * <p>默认实现退化为仅使用当前用户消息的旧行为；角色实现类覆盖本方法完成消息组装。</p>
     *
     * @param invocation 系统提示词、历史与当前用户消息的组合入参
     * @return 文本增量流
     */
    default Flux<String> streamChat(ChatInvocation invocation) {
        return streamChat(invocation.userMessage());
    }

    /**
     * D16：带思考模式设置的同步聊天，返回推理内容与最终答案分离的结构化结果。
     *
     * <p>默认实现委托旧的 {@link #chat(ChatInvocation)}，保证未改造的实现类保持旧行为；
     * Mock/DeepSeek 两个实现分别覆写以支持思考开关与 reasoning_content 分离。</p>
     *
     * @param invocation 系统提示词、历史与当前用户消息
     * @param settings   服务端裁决后的思考设置
     * @return 结构化聊天结果
     */
    default AiChatResult chat(ChatInvocation invocation, ThinkingSettings settings) {
        ChatReplyResponse legacy = chat(invocation);
        return new AiChatResult(legacy.getMode(), legacy.getModel(),
                Boolean.TRUE.equals(legacy.getMocked()), settings.enabled(),
                legacy.getContent(), null);
    }

    /**
     * D16：带思考模式设置的流式聊天，返回类型化的推理/答案分片。
     *
     * <p>默认实现把旧的文本流统一包装为 {@link StreamChunk.ContentChunk}，
     * 支持思考的实现类覆写后可额外发射 {@link StreamChunk.ReasoningChunk}。</p>
     *
     * @param invocation 系统提示词、历史与当前用户消息
     * @param settings   服务端裁决后的思考设置
     * @return 类型化分片流
     */
    default Flux<StreamChunk> streamChat(ChatInvocation invocation, ThinkingSettings settings) {
        return streamChat(invocation).map(StreamChunk.ContentChunk::new);
    }

    /**
     * D16：无角色链路的带设置同步聊天便捷重载，由普通消息构造最小调用入参。
     */
    default AiChatResult chat(String message, ThinkingSettings settings) {
        return chat(new ChatInvocation(null, message, List.of()), settings);
    }

    /**
     * D16：无角色链路的带设置流式聊天便捷重载。
     */
    default Flux<StreamChunk> streamChat(String message, ThinkingSettings settings) {
        return streamChat(new ChatInvocation(null, message, List.of()), settings);
    }
}
