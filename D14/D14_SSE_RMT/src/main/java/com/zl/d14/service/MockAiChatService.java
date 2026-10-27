package com.zl.d14.service;

import com.zl.d14.config.AiConstants;
import com.zl.d14.dto.ChatReplyResponse;
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
     * 同步与流式共用同一份文案，保证两种接口的最终文本一致，避免两套口径漂移。
     */
    private String buildMockContent(String message) {
        return AiConstants.MOCK_REPLY_MARKER
                + " 当前为离线模拟模式（AI_MODE=mock），未发起任何 DeepSeek 网络请求。"
                + "已收到问题：「" + message + "」。"
                + "如需真实回答，请设置环境变量 AI_MODE=deepseek 并注入有效的 DEEPSEEK_API_KEY。";
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
