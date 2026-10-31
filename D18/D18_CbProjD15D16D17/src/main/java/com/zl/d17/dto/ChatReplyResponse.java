package com.zl.d17.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 聊天接口响应数据体。
 *
 * <p>通过 {@link #mocked} 与 {@link #mode} 明确区分离线模拟结果与 DeepSeek 真实结果。
 * D16 新增思考模式与引用来源字段：推理内容与最终答案严格分离，引用列表只承载后端校验过的真实来源；
 * 类级 NON_NULL 使旧链路不产生多余 null 字段，保持向后兼容。</p>
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatReplyResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际处理模式：mock 或 deepseek。 */
    private String mode;

    /** 模型标识：模拟模式为 offline-mock；真实模式为实际配置的 DeepSeek 模型名。 */
    private String model;

    /** 是否为模拟结果（true 表示未发起真实模型调用）。 */
    private Boolean mocked;

    /** 回复文本内容（最终答案，不含模型原始推理）。 */
    private String content;

    /** D16 本次请求是否启用思考模式；旧链路构造时为 null，序列化隐藏。 */
    private Boolean thinkingEnabled;

    /**
     * D17 会话标识。普通链路新建会话后回传，客户端后续请求携带即可保持多轮上下文；
     * 旧调用方无该字段时为 null，NON_NULL 保证序列化形态向后兼容。
     */
    private String sessionId;

    /**
     * D16 模型原始推理内容（reasoning_content）。
     * 仅当 thinkingEnabled=true 且 showReasoning=true 时才填充；其余情况为 null，
     * 且该字段禁止写入会话记忆与普通业务日志。
     */
    private String reasoningContent;

    /** D16 经后端校验的引用来源列表；无检索或无命中时为空列表（不返回 null）。 */
    private List<CitationDto> citations;

    /** D16 全参构造。 */
    public ChatReplyResponse(String mode, String model, Boolean mocked, String content,
                             Boolean thinkingEnabled, String reasoningContent,
                             List<CitationDto> citations) {
        this.mode = mode;
        this.model = model;
        this.mocked = mocked;
        this.content = content;
        this.thinkingEnabled = thinkingEnabled;
        this.reasoningContent = reasoningContent;
        this.citations = citations;
    }

    /**
     * 旧四参构造（D16 之前的调用方与测试兼容入口）：思考字段为 null、引用为空列表，
     * 序列化形态与旧版本保持一致。
     */
    public ChatReplyResponse(String mode, String model, Boolean mocked, String content) {
        this(mode, model, mocked, content, null, null, List.of());
    }
}
