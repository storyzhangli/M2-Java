package com.zl.d17.service;

/**
 * 流式输出的类型化分片。
 *
 * <p>旧链路的 {@code Flux<String>} 只能表达“文本增量”，无法区分 DeepSeek 思考模式下与最终答案
 * 同级返回的 {@code reasoning_content} 推理增量。D16 起模型适配层统一发射本密封类型，
 * 由 SSE 桥接层分别映射为 {@code thinking} 与 {@code delta} 事件，从类型上杜绝推理文本混入最终答案。</p>
 */
public sealed interface StreamChunk permits StreamChunk.ReasoningChunk, StreamChunk.ContentChunk {

    /** 文本片段内容，两个子类各自承载，便于桥接层统一读取。 */
    String text();

    /**
     * 推理过程分片（对应 DeepSeek 的 reasoning_content）。
     *
     * <p>仅在思考模式且用户显式要求展示推理过程时才会被 SSE 层下发给客户端；
     * 不允许进入会话记忆与普通业务日志。</p>
     */
    record ReasoningChunk(String text) implements StreamChunk {
    }

    /** 最终答案分片（对应 content），是唯一允许追加进聊天答案与会话历史的内容。 */
    record ContentChunk(String text) implements StreamChunk {
    }
}
