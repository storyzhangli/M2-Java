package com.zl.d23.adapter.deepseek;

import com.zl.d23.adapter.ChatModelPort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 的 {@link ChatModelPort} 实现。
 *
 * <p>本类是 DeepSeek 专有类型（{@link DeepSeekChatOptions}、{@link DeepSeekAssistantMessage}）
 * 的受控边界：供应商类型只在本适配包内出现，业务服务层只依赖中立的 {@link ChatModelPort}。
 * 仅在 {@code app.ai.mode=deepseek} 时装配。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "deepseek")
public class DeepSeekChatAdapter implements ChatModelPort {

    /**
     * 构造 DeepSeek 专有的每次请求选项，并以可移植 {@link ChatOptions} 类型返回。
     */
    @Override
    public ChatOptions requestOptions(String model) {
        return DeepSeekChatOptions.builder().model(model).build();
    }

    /**
     * 仅当运行时消息为 DeepSeek 专有子类型时读取 reasoning_content，否则返回 null。
     */
    @Override
    public String reasoningContent(AssistantMessage message) {
        if (message instanceof DeepSeekAssistantMessage deepSeekMessage) {
            return deepSeekMessage.getReasoningContent();
        }
        return null;
    }
}
