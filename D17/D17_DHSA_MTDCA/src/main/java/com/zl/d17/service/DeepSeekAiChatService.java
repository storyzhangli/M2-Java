package com.zl.d17.service;

import com.zl.d17.common.AiConfigurationException;
import com.zl.d17.config.AiConstants;
import com.zl.d17.dto.ChatReplyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

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

    /**
     * D16 思考模式实际切换到的推理模型名，服务端配置白名单，客户端无法通过请求 DTO 指定任意模型。
     * 默认 deepseek-reasoner（Spring AI 1.1.8 内置 ChatModel 枚举支持的两个模型之一），
     * 可由环境变量 DEEPSEEK_REASONER_MODEL 覆盖。
     */
    private final String reasonerModel;

    public DeepSeekAiChatService(
            ChatModel chatModel,
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}") String model,
            @Value("${app.ai.thinking.reasoner-model:deepseek-reasoner}") String reasonerModel) {
        this.chatModel = chatModel;
        this.apiKey = apiKey;
        this.model = model;
        this.reasonerModel = reasonerModel;
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

    // ------------------------------------------------------------------
    // D16 思考模式（Thinking Mode）：通过按请求覆盖模型为 deepseek-reasoner 开启，
    // 复用同一 ChatModel 调用链，不新增第二套 HTTP 客户端。
    // ------------------------------------------------------------------

    /**
     * D16 带思考设置的同步调用。
     *
     * <p>Spring AI 1.1.8 不支持 thinking/reasoning_effort 请求参数（已核验 jar），思考开关只能通过
     * 选择 deepseek-reasoner 模型实现；响应中的 reasoning_content 由
     * {@link DeepSeekAssistantMessage#getReasoningContent()} 单独取出，绝不与 content 拼接。</p>
     */
    @Override
    public AiChatResult chat(ChatInvocation invocation, ThinkingSettings settings) {
        validateCredential();
        String effectiveModel = effectiveModel(settings);
        Prompt prompt = new Prompt(buildMessages(invocation), buildModelOptions(effectiveModel));
        log.info("D16 角色对话同步调用，thinkingEnabled={}，model={}，历史消息数={}",
                settings.enabled(), effectiveModel, invocation.history().size());

        ChatResponse response = this.chatModel.call(prompt);
        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText();
        String reasoning = extractReasoning(output);
        // 推理全文不入日志，仅记录长度，避免内部思维链泄漏到业务审计日志
        log.info("D16 同步调用完成，model={}，answerLength={}，reasoningLength={}",
                effectiveModel, content == null ? 0 : content.length(),
                reasoning == null ? 0 : reasoning.length());
        return new AiChatResult(AiConstants.MODE_DEEPSEEK, effectiveModel, false,
                settings.enabled(), content, reasoning);
    }

    @Override
    public AiChatResult chat(String message, ThinkingSettings settings) {
        validateCredential();
        String effectiveModel = effectiveModel(settings);
        Prompt prompt = new Prompt(List.of(new UserMessage(message)), buildModelOptions(effectiveModel));
        log.info("D16 同步调用，thinkingEnabled={}，model={}", settings.enabled(), effectiveModel);

        ChatResponse response = this.chatModel.call(prompt);
        AssistantMessage output = response.getResult().getOutput();
        String reasoning = extractReasoning(output);
        return new AiChatResult(AiConstants.MODE_DEEPSEEK, effectiveModel, false,
                settings.enabled(), output.getText(), reasoning);
    }

    /**
     * D16 带思考设置的流式调用。每个 ChatResponse 分片可能携带推理增量、答案增量或二者之一，
     * 这里拆成独立的类型化分片并保持到达顺序；使用 concatMap 保证分片顺序不被并发打乱。
     */
    @Override
    public reactor.core.publisher.Flux<StreamChunk> streamChat(ChatInvocation invocation,
                                                               ThinkingSettings settings) {
        validateCredential();
        String effectiveModel = effectiveModel(settings);
        Prompt prompt = new Prompt(buildMessages(invocation), buildModelOptions(effectiveModel));
        log.info("D16 角色对话流式调用，thinkingEnabled={}，model={}，历史消息数={}",
                settings.enabled(), effectiveModel, invocation.history().size());
        return toTypedStream(prompt, effectiveModel);
    }

    @Override
    public reactor.core.publisher.Flux<StreamChunk> streamChat(String message, ThinkingSettings settings) {
        validateCredential();
        String effectiveModel = effectiveModel(settings);
        Prompt prompt = new Prompt(List.of(new UserMessage(message)), buildModelOptions(effectiveModel));
        log.info("D16 流式调用，thinkingEnabled={}，model={}", settings.enabled(), effectiveModel);
        return toTypedStream(prompt, effectiveModel);
    }

    /**
     * 把模型原生 ChatResponse 流拆成推理/答案两类分片；空块与空白文本均过滤。
     */
    private reactor.core.publisher.Flux<StreamChunk> toTypedStream(Prompt prompt, String effectiveModel) {
        return this.chatModel.stream(prompt)
                .concatMap(chunk -> {
                    List<StreamChunk> pieces = new ArrayList<>(2);
                    if (chunk == null || chunk.getResult() == null
                            || chunk.getResult().getOutput() == null) {
                        return reactor.core.publisher.Flux.<StreamChunk>empty();
                    }
                    AssistantMessage output = chunk.getResult().getOutput();
                    String reasoning = extractReasoning(output);
                    if (StringUtils.hasText(reasoning)) {
                        pieces.add(new StreamChunk.ReasoningChunk(reasoning));
                    }
                    if (StringUtils.hasText(output.getText())) {
                        pieces.add(new StreamChunk.ContentChunk(output.getText()));
                    }
                    return reactor.core.publisher.Flux.fromIterable(pieces);
                })
                .doOnComplete(() -> log.info("D16 流式调用正常结束，model={}", effectiveModel))
                .doOnError(ex -> log.error("D16 流式调用异常，异常类型: {}", ex.getClass().getSimpleName()));
    }

    /**
     * 按请求构造模型覆盖选项；1.1.8 仅支持模型级开关，不存在 effort 字段，故此处只设置 model。
     */
    private DeepSeekChatOptions buildModelOptions(String effectiveModel) {
        return DeepSeekChatOptions.builder().model(effectiveModel).build();
    }

    private String effectiveModel(ThinkingSettings settings) {
        return settings.enabled() ? this.reasonerModel : this.model;
    }

    /**
     * 仅当输出是 DeepSeek 专属消息类型时才能取到 reasoning_content；普通 AssistantMessage 返回 null。
     */
    private String extractReasoning(AssistantMessage output) {
        if (output instanceof DeepSeekAssistantMessage deepSeekOutput) {
            return deepSeekOutput.getReasoningContent();
        }
        return null;
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
