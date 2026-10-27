package com.zl.d01.springai.service;

import com.zl.d01.springai.common.AiConfigurationException;
import com.zl.d01.springai.config.AiConstants;
import com.zl.d01.springai.dto.ChatReplyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * DeepSeek 真实聊天服务。
 *
 * <p>仅当 {@code app.ai.mode=deepseek} 时装配。注入 Spring AI 自动配置产出的 {@link ChatModel}
 * （1.1.8 下唯一实现为 {@code DeepSeekChatModel}），通过受支持的可移植抽象发起同步阻塞调用，
 * 而非直接操作底层 HTTP 客户端。</p>
 *
 * <p>安全约束：每次调用前先校验密钥，缺失/空白/占位密钥一律抛出
 * {@link AiConfigurationException}，<b>在发起外部请求之前快速失败</b>；本类不打印、不回显密钥。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "mode",
        havingValue = AiConstants.MODE_DEEPSEEK
)
public class DeepSeekAiChatService implements AiChatService {

    private final ChatModel chatModel;

    private final String apiKey;

    private final String model;

    public DeepSeekAiChatService(
            ChatModel chatModel,
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}") String model) {
        this.chatModel = chatModel;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ChatReplyResponse chat(String message) {
        // 调用前置校验：占位/空白密钥绝不允许触发真实外部请求
        validateCredential();

        Prompt prompt = new Prompt(new UserMessage(message));
        log.info("接收的prompt消息: {}", prompt);
        ChatResponse response = this.chatModel.call(prompt);
        log.info("响应消息: {}",response.toString());
        String content = response.getResult().getOutput().getText();

        return new ChatReplyResponse(
                AiConstants.MODE_DEEPSEEK,
                this.model,
                Boolean.FALSE,
                content
        );
    }

    private void validateCredential() {
        if (!StringUtils.hasText(this.apiKey)
                || AiConstants.PLACEHOLDER_API_KEY.equals(this.apiKey)
                || this.apiKey.toLowerCase().contains("mock")) {
            throw new AiConfigurationException(
                    "deepseek 真实模式未配置有效密钥：DEEPSEEK_API_KEY 缺失、为空白或仍为占位密钥，已阻止发起外部请求");
        }
    }
}
