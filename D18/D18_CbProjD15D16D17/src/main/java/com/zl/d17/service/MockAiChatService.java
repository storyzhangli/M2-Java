package com.zl.d17.service;

import com.zl.d17.config.AiConstants;
import com.zl.d17.dto.ChatReplyResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线模拟聊天服务（默认实现）。
 *
 * <p>当 {@code app.ai.mode} 缺失（{@code matchIfMissing=true}）或显式为 {@code mock} 时装配。
 * 本类<b>不注入任何 ChatModel / HTTP 客户端，也不声明任何字段</b>，仅做纯字符串拼装，从结构上保证
 * 模拟模式不可能发起真实 DeepSeek 请求；相同输入始终得到相同输出（确定性），便于离线自动化测试。</p>
 *
 * <p>流式能力 {@link #streamChat(String)} 将同一份确定性文案切分为固定大小的字符块，按固定节奏发射，
 * 用于在无外网环境下真实地演示“分段到达、顺序追加”的 SSE 效果；分块仅为演示，不代表模型 token 边界。
 * 分块大小与发射间隔为方法内局部常量，不提升为字段，以满足“模拟服务零字段、无参构造”的结构约束。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "mode",
        havingValue = AiConstants.MODE_MOCK,
        matchIfMissing = true
)
public class MockAiChatService implements AiChatService {

    @Override
    public ChatReplyResponse chat(String message) {
        return new ChatReplyResponse(
                AiConstants.MODE_MOCK,
                "offline-mock",
                Boolean.TRUE,
                buildMockContent(message)
        );
    }

    @Override
    public Flux<String> streamChat(String message) {
        List<String> chunks = splitToChunks(buildMockContent(message), 6);
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(15));
    }

    /**
     * 角色链路的模拟同步调用：确定性回显所使用的系统提示词长度与历史轮次，
     * 便于在无外网环境下断言“确实选用了对应角色模板、历史确实按会话传递”。
     */
    @Override
    public ChatReplyResponse chat(ChatInvocation invocation) {
        return new ChatReplyResponse(
                AiConstants.MODE_MOCK,
                "offline-mock",
                Boolean.TRUE,
                buildRoleMockContent(invocation)
        );
    }

    /**
     * 角色链路的模拟流式调用：与同步共用同一份确定性文案。
     */
    @Override
    public Flux<String> streamChat(ChatInvocation invocation) {
        List<String> chunks = splitToChunks(buildRoleMockContent(invocation), 6);
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(15));
    }

    private String buildRoleMockContent(ChatInvocation invocation) {
        String systemPrompt = invocation.systemPrompt() == null ? "" : invocation.systemPrompt();
        String firstLine = systemPrompt.lines().findFirst().orElse("");
        return AiConstants.MOCK_REPLY_MARKER + " 当前为离线模拟角色对话（AI_MODE=mock），未发起任何外部请求。"
                + "系统提示词字符数=" + systemPrompt.length()
                + "，历史消息条数=" + invocation.history().size()
                + "，系统提示词首行=" + firstLine
                + "；已收到问题：「" + invocation.userMessage() + "」。";
    }

    /**
     * 同步与流式共用同一份文案，保证两种接口的最终文本一致，避免两套口径漂移。
     */
    private String buildMockContent(String message) {
        return AiConstants.MOCK_REPLY_MARKER
                + " 当前为离线模拟模式（AI_MODE=mock），未发起任何 DeepSeek 网络请求。"
                + "已收到问题：「" + message + "」。"
                + "如需真实回答，请设置环境变量 AI_MODE=deepseek 并注入有效的 DEEPSEEK_API_KEY。";
    }

    /**
     * D16：带思考设置的模拟同步调用。
     *
     * <p>思考开启时产出一段确定性的“模拟推理文本”，放在 {@link AiChatResult#reasoningContent()}，
     * 与最终答案严格分离；思考关闭时 reasoningContent 为 null，结果与旧链路文本一致。</p>
     */
    @Override
    public AiChatResult chat(ChatInvocation invocation, ThinkingSettings settings) {
        String content = settings.enabled()
                ? buildThinkingMockContent(invocation)
                : buildRoleMockContent(invocation);
        String reasoning = settings.enabled() ? buildMockReasoning(invocation) : null;
        return new AiChatResult(AiConstants.MODE_MOCK, "offline-mock", true,
                settings.enabled(), content, reasoning);
    }

    /**
     * D16：无角色链路的带设置模拟同步调用。
     */
    @Override
    public AiChatResult chat(String message, ThinkingSettings settings) {
        String content = settings.enabled()
                ? buildThinkingPlainContent(message)
                : buildMockContent(message);
        String reasoning = settings.enabled() ? buildMockReasoning(message) : null;
        return new AiChatResult(AiConstants.MODE_MOCK, "offline-mock", true,
                settings.enabled(), content, reasoning);
    }

    /**
     * D16：带思考设置的模拟流式调用。思考开启时先发射推理分片、再发射答案分片，
     * 两类分片类型不同，SSE 桥接层据此分发到 thinking/delta 事件，不会互相混入。
     */
    @Override
    public Flux<StreamChunk> streamChat(ChatInvocation invocation, ThinkingSettings settings) {
        List<StreamChunk> chunks = new ArrayList<>();
        String content = settings.enabled()
                ? buildThinkingMockContent(invocation)
                : buildRoleMockContent(invocation);
        if (settings.enabled()) {
            splitToChunks(buildMockReasoning(invocation), 6)
                    .forEach(c -> chunks.add(new StreamChunk.ReasoningChunk(c)));
        }
        splitToChunks(content, 6).forEach(c -> chunks.add(new StreamChunk.ContentChunk(c)));
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(15));
    }

    /**
     * D16：无角色链路的带设置模拟流式调用。
     */
    @Override
    public Flux<StreamChunk> streamChat(String message, ThinkingSettings settings) {
        List<StreamChunk> chunks = new ArrayList<>();
        String content = settings.enabled()
                ? buildThinkingPlainContent(message)
                : buildMockContent(message);
        if (settings.enabled()) {
            splitToChunks(buildMockReasoning(message), 6)
                    .forEach(c -> chunks.add(new StreamChunk.ReasoningChunk(c)));
        }
        splitToChunks(content, 6).forEach(c -> chunks.add(new StreamChunk.ContentChunk(c)));
        return Flux.fromIterable(chunks).delayElements(Duration.ofMillis(15));
    }

    /**
     * 确定性模拟推理文本：固定结构、无随机、无外部请求，仅用于离线演示 reasoning/content 分流。
     */
    private String buildMockReasoning(ChatInvocation invocation) {
        return AiConstants.MOCK_REASONING_MARKER + " 离线模拟思考过程（不访问外部模型）："
                + "已识别问题意图，历史消息条数=" + invocation.history().size()
                + "，将按确定性规则组织最终回答；本段内容默认不展示给终端用户。";
    }

    /** 无角色链路的模拟推理文本。 */
    private String buildMockReasoning(String message) {
        return AiConstants.MOCK_REASONING_MARKER + " 离线模拟思考过程（不访问外部模型）："
                + "已收到问题「" + message + "」，本段为受控演示的推理占位文本，与最终答案分离存储。";
    }

    /** 思考开启时角色链路的最终答案文案（不含任何推理文本）。 */
    private String buildThinkingMockContent(ChatInvocation invocation) {
        return AiConstants.MOCK_REPLY_MARKER + " 当前为离线模拟思考模式（AI_MODE=mock），未发起外部请求。"
                + "历史消息条数=" + invocation.history().size()
                + "，最终回答：「" + invocation.userMessage() + "」。";
    }

    /** 思考开启时无角色链路的最终答案文案。 */
    private String buildThinkingPlainContent(String message) {
        return AiConstants.MOCK_REPLY_MARKER + " 当前为离线模拟思考模式（AI_MODE=mock），"
                + "推理过程与最终答案已分离。最终回答针对问题：「" + message + "」。";
    }

    /**
     * 按固定字符数切分，最后一块可能不足 {@code chunkSize}；输入非空时至少产生一块。
     */
    private List<String> splitToChunks(String content, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < content.length(); i += chunkSize) {
            chunks.add(content.substring(i, Math.min(i + chunkSize, content.length())));
        }
        return chunks;
    }
}
