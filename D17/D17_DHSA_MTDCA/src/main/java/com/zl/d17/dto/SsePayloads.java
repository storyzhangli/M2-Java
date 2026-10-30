package com.zl.d17.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SSE（Server-Sent Events，服务器发送事件）各命名事件的数据载荷。
 *
 * <p>统一放在此处是为了让前后端对事件数据结构有唯一、稳定的契约；全部为不可变 record，
 * 由 Jackson 序列化为 JSON 后写入 SSE {@code data:} 字段，避免手工拼接造成帧格式非法。</p>
 *
 * <ul>
 *     <li>{@link Start}：流开始，携带请求标识与模式；</li>
 *     <li>{@link Thinking}：D16 推理过程增量，仅在用户显式要求展示时发送；</li>
 *     <li>{@link Delta}：最终答案文本增量，{@code seq} 从 0 递增，前端按序追加；</li>
 *     <li>{@link Citations}：D16 引用来源，必须在 complete 之前发送；</li>
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
     * <p>D15 新增会话/模板溯源字段、D16 新增思考开关字段，仅相应链路非空；类级 {@link JsonInclude}
     * 保证 null 字段不出现在 JSON 中，旧的无角色链路帧结构保持不变，前端与既有测试不受影响。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Start(String requestId, String mode, String model,
                        String sessionId, String scene, String templateId, Integer templateVersion,
                        Boolean thinkingEnabled) {

        /** D16 全参构造。 */
        public Start(String requestId, String mode, String model,
                     String sessionId, String scene, String templateId, Integer templateVersion,
                     Boolean thinkingEnabled) {
            this.requestId = requestId;
            this.mode = mode;
            this.model = model;
            this.sessionId = sessionId;
            this.scene = scene;
            this.templateId = templateId;
            this.templateVersion = templateVersion;
            this.thinkingEnabled = thinkingEnabled;
        }

        /** 旧链路构造：溯源与思考字段全部为 null，序列化后与历史帧完全一致。 */
        public Start(String requestId, String mode, String model) {
            this(requestId, mode, model, null, null, null, null, null);
        }
    }

    /**
     * D16 推理增量事件载荷（对应 reasoning_content）。
     * seq 独立于答案 delta 计数，前端渲染到独立“思考过程”区域，不允许追加进答案正文。
     */
    public record Thinking(int seq, String content) {
    }

    /** 最终答案文本增量事件载荷。 */
    public record Delta(int seq, String content) {
    }

    /**
     * D16 引用来源事件载荷；在 complete 之前发送一次，列表只含后端注册表中的真实来源。
     */
    public record Citations(List<CitationDto> citations) {
    }

    /**
     * 正常完成事件载荷。thinkingEnabled 为可空扩展字段，旧构造路径下序列化隐藏。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Complete(int seq, String mode, String model, boolean mocked, int totalChars,
                           Boolean thinkingEnabled) {

        /** 旧五参构造（D16 之前测试与前端兼容入口）。 */
        public Complete(int seq, String mode, String model, boolean mocked, int totalChars) {
            this(seq, mode, model, mocked, totalChars, null);
        }
    }

    /** 错误事件载荷（对外安全结构）。 */
    public record Error(int code, String message) {
    }
}
