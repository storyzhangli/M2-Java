package com.zl.d22.adapter;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 供应商中立的模型端口（Chat Model Port）。
 *
 * <p>把两类原本会泄漏供应商专有类型的操作收敛到适配边界：</p>
 * <ul>
 *     <li>{@link #requestOptions(String)}：按本次请求构造可移植 {@link ChatOptions}，
 *     隐藏供应商专有的 Options 类型；</li>
 *     <li>{@link #reasoningContent(AssistantMessage)}：从助手消息中提取推理内容，
 *     隐藏供应商专有的 AssistantMessage 子类型。</li>
 * </ul>
 *
 * <p>本接口只引用 Spring AI 的可移植抽象，不引用任何供应商专有类型；
 * 具体供应商实现放在 {@code adapter.<vendor>} 子包。</p>
 */
public interface ChatModelPort {

    /**
     * 按本次请求构造可移植的 ChatOptions。
     *
     * @param model 本次生效的模型名
     * @return 仅携带模型名等必要参数的可移植选项
     */
    ChatOptions requestOptions(String model);

    /**
     * 从助手消息提取推理内容（reasoning content）。
     *
     * @param message Spring AI 助手消息（运行时可能是供应商专有子类型）
     * @return 推理文本；非推理模型或无推理内容时返回 {@code null}
     */
    String reasoningContent(AssistantMessage message);
}
