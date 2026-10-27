package com.zl.d14.springai.service;

import com.zl.d14.config.AiConstants;
import com.zl.d14.service.MockAiChatService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线模拟流式服务的纯单元测试（不启动 Spring 容器、不访问外网）。
 */
class MockAiChatServiceStreamTest {

    private final MockAiChatService service = new MockAiChatService();

    @Test
    void streamShouldEmitMultipleOrderedNonBlankChunksAndMatchSyncContent() {
        List<String> chunks = service.streamChat("流式单元测试")
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull();
        // 必须是“多段”流式输出，而不是一次性返回整句
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).noneMatch(String::isBlank);

        String joined = String.join("", chunks);
        assertThat(joined).startsWith(AiConstants.MOCK_REPLY_MARKER);
        assertThat(joined).contains("流式单元测试");
        // 流式拼接结果必须与同步接口文案完全一致，避免两套口径漂移
        assertThat(joined).isEqualTo(service.chat("流式单元测试").getContent());
    }
}
