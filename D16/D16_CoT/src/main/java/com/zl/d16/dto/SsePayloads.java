package com.zl.d16.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE（Server-Sent Events，服务器发送事件）各命名事件的数据载荷。
 *
 * <p>统一放在此处是为了让前后端对事件数据结构有唯一、稳定的契约；全部为不可变 record，
 * 由 Jackson 序列化为 JSON 后写入 SSE {@code data:} 字段，避免手工拼接造成帧格式非法。</p>
 *
 * <ul>
 *     <li>{@link Start}：流开始，携带请求标识与模式；</li>
 *     <li>{@link Delta}：文本增量，{@code seq} 从 0 递增，前端按序追加；</li>
 *     <li>{@link Complete}：正常结束汇总，之后不会再有任何内容事件；</li>
 *     <li>{@link Error}：异常事件，携带安全错误码与固定文案（不含堆栈、密钥、上游原文）。</li>
 * </ul>
 */
public final class SsePayloads {

    private SsePayloads() {
    }

    /**
     * 流开始事件载荷。
     *
     * <p>D15 新增会话/模板溯源字段，仅角色对话链路非空；类级 {@link JsonInclude} 保证 null 字段不出现在
     * JSON 中，旧的无角色链路帧结构保持不变，前端与既有测试不受影响。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Start(String requestId, String mode, String model,
                        String sessionId, String scene, String templateId, Integer templateVersion) {

        /** 旧链路构造：溯源字段全部为 null，序列化后与 D14 帧完全一致。 */
        public Start(String requestId, String mode, String model) {
            this(requestId, mode, model, null, null, null, null);
        }
    }

    /** 文本增量事件载荷。 */
    public record Delta(int seq, String content) {
    }

    /** 正常完成事件载荷。 */
    public record Complete(int seq, String mode, String model, boolean mocked, int totalChars) {
    }

    /** 错误事件载荷（对外安全结构）。 */
    public record Error(int code, String message) {
    }
}
