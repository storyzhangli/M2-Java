package com.zl.d23.adapter.deepseek;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeepSeek 模型端口适配测试：验证供应商专有类型在适配边界内被正确构造与读取。
 * 不启动 Spring 上下文、不触外网。
 */
class DeepSeekChatAdapterTest {

    private final DeepSeekChatAdapter adapter = new DeepSeekChatAdapter();

    @Test
    @DisplayName("requestOptions：返回 DeepSeek 专有选项且模型名正确下发")
    void requestOptionsCarriesModel() {
        ChatOptions options = adapter.requestOptions("deepseek-flash");

        assertThat(options).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(((DeepSeekChatOptions) options).getModel()).isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("reasoningContent：DeepSeek 专有消息返回 reasoning_content")
    void reasoningFromDeepSeekMessage() {
        DeepSeekAssistantMessage message = mock(DeepSeekAssistantMessage.class);
        when(message.getReasoningContent()).thenReturn("chain-of-thought");

        assertThat(adapter.reasoningContent(message)).isEqualTo("chain-of-thought");
    }

    @Test
    @DisplayName("reasoningContent：普通 AssistantMessage 不是 DeepSeek 子类型，返回 null")
    void reasoningFromPlainMessageIsNull() {
        AssistantMessage message = new AssistantMessage("plain");

        assertThat(adapter.reasoningContent(message)).isNull();
    }
}
