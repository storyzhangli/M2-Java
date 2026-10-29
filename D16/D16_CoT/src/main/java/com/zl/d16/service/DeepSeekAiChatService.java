package com.zl.d16.service;

import com.zl.d16.common.AiConfigurationException;
import com.zl.d16.config.AiConstants;
import com.zl.d16.dto.ChatReplyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

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

    @Override
    public Flux<String> streamChat(String message) {
        // 与同步调用相同的前置校验：占位/空白密钥绝不允许触发真实外部请求
        validateCredential();

        Prompt prompt = new Prompt(new UserMessage(message));
        log.info("开始流式调用 DeepSeek，model={}", this.model);

        // ChatModel.stream 返回逐块 ChatResponse；这里只抽取文本增量并过滤空块，
        // 不假设每块对应一个 token/单词/整句，异常原样向上传播交由 Controller 转 SSE error 事件。
        return this.chatModel.stream(prompt)
                .map(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return null;
                    }
                    return chunk.getResult().getOutput().getText();
                })
                .filter(StringUtils::hasText)
                .doOnComplete(() -> log.info("流式调用正常结束，model={}", this.model))
                .doOnError(ex -> log.error("流式调用异常，异常类型: {}", ex.getClass().getSimpleName()));
    }

    /**
     * 角色链路同步调用：系统角色提示词 → 历史消息 → 当前用户消息，按消息角色独立组装，
     * 用户输入只进入 UserMessage，无法覆盖 SystemMessage 中的角色规则。
     */
    @Override
    public ChatReplyResponse chat(ChatInvocation invocation) {
        validateCredential();

        Prompt prompt = new Prompt(buildMessages(invocation));
        log.info("角色对话同步调用，model={}，历史消息数={}", this.model, invocation.history().size());
        ChatResponse response = this.chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();

        return new ChatReplyResponse(AiConstants.MODE_DEEPSEEK, this.model, Boolean.FALSE, content);
    }

    /**
     * 角色链路流式调用，消息组装顺序与同步链路保持一致。
     */
    @Override
    public Flux<String> streamChat(ChatInvocation invocation) {
        validateCredential();

        Prompt prompt = new Prompt(buildMessages(invocation));
        log.info("角色对话流式调用，model={}，历史消息数={}", this.model, invocation.history().size());

        return this.chatModel.stream(prompt)
                .map(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return null;
                    }
                    return chunk.getResult().getOutput().getText();
                })
                .filter(StringUtils::hasText)
                .doOnComplete(() -> log.info("角色对话流式调用正常结束，model={}", this.model))
                .doOnError(ex -> log.error("角色对话流式调用异常，异常类型: {}", ex.getClass().getSimpleName()));
    }

    /**
     * 按“系统提示词 + 多轮历史 + 当前用户消息”的顺序组装模型消息列表。
     */
    private List<Message> buildMessages(ChatInvocation invocation) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(invocation.systemPrompt())) {
            messages.add(new SystemMessage(invocation.systemPrompt()));
        }
        for (ChatInvocation.Turn turn : invocation.history()) {
            if ("assistant".equals(turn.role())) {
                messages.add(new AssistantMessage(turn.content()));
            } else {
                messages.add(new UserMessage(turn.content()));
            }
        }
        messages.add(new UserMessage(invocation.userMessage()));
        return messages;
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
