package com.zl.d01.springai.service;

import com.zl.d01.springai.config.AiConstants;
import com.zl.d01.springai.dto.ChatReplyResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 离线模拟聊天服务（默认实现）。
 *
 * <p>当 {@code app.ai.mode} 缺失（{@code matchIfMissing=true}）或显式为 {@code mock} 时装配。
 * 本类<b>不注入任何 ChatModel / HTTP 客户端</b>，仅做纯字符串拼装，从结构上保证模拟模式不可能
 * 发起真实 DeepSeek 请求；相同输入始终得到相同输出（确定性），便于离线自动化测试。</p>
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
        String content = AiConstants.MOCK_REPLY_MARKER
                + " 当前为离线模拟模式（AI_MODE=mock），未发起任何 DeepSeek 网络请求。"
                + "已收到问题：「" + message + "」。"
                + "如需真实回答，请设置环境变量 AI_MODE=deepseek 并注入有效的 DEEPSEEK_API_KEY。";
        return new ChatReplyResponse(
                AiConstants.MODE_MOCK,
                "offline-mock",
                Boolean.TRUE,
                content
        );
    }
}
