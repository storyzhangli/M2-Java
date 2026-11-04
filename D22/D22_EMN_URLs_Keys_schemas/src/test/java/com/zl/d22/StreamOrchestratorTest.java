package com.zl.d22;

import com.zl.d22.knowledge.KnowledgeChatOrchestrator;
import com.zl.d22.knowledge.KnowledgeRetrievalService;
import com.zl.d22.service.AiChatService;
import com.zl.d22.service.ChatInvocation;
import com.zl.d22.service.StreamChunk;
import com.zl.d22.service.ThinkingSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式编排链路测试（T03/T04/T05）。
 *
 * <p>在服务/编排层验证 Flux 多片段、同步与流式同源、以及上游异常沿 Reactor 传播。
 * 测试中使用 block/collectList 聚合结果（仅测试代码，生产链路不阻塞）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class StreamOrchestratorTest {

    @Autowired
    private KnowledgeChatOrchestrator orchestrator;

    @Test
    @DisplayName("T03 流式返回多个内容分片且可拼合")
    void stream_emitsMultipleContentChunks() {
        ChatInvocation invocation = new ChatInvocation(null, "设备无法开机", List.of());
        Flux<StreamChunk> chunks = orchestrator.stream(invocation,
                ThinkingSettings.legacyDefault(), false).chunks();

        List<StreamChunk> all = chunks.collectList().block();
        assertNotNull(all);
        long contentCount = all.stream().filter(c -> c instanceof StreamChunk.ContentChunk).count();
        assertTrue(contentCount >= 2, "应至少有两个内容分片，实际=" + contentCount);

        String joined = all.stream()
                .filter(c -> c instanceof StreamChunk.ContentChunk)
                .map(StreamChunk::text)
                .reduce("", String::concat);
        assertFalse(joined.isBlank());
    }

    @Test
    @DisplayName("T04 同一输入同步与流式最终文本同源")
    void syncAndStream_shareSameContent() {
        ChatInvocation invocation = new ChatInvocation(null, "设备无法开机", List.of());

        String syncContent = orchestrator.chat(invocation,
                ThinkingSettings.legacyDefault(), false).result().content();

        String streamContent = orchestrator.stream(invocation,
                ThinkingSettings.legacyDefault(), false).chunks()
                .filter(c -> c instanceof StreamChunk.ContentChunk)
                .map(StreamChunk::text)
                .collectList().block().stream().reduce("", String::concat);

        assertEquals(syncContent, streamContent);
    }

    @Test
    @DisplayName("T05 上游异常沿 Flux 传播而不伪造完成")
    void stream_errorPropagates() {
        AiChatService failing = mock(AiChatService.class);
        when(failing.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream failure")));

        KnowledgeChatOrchestrator unit = new KnowledgeChatOrchestrator(failing,
                mock(KnowledgeRetrievalService.class), "mock");
        ChatInvocation invocation = new ChatInvocation(null, "hi", List.of());

        Flux<StreamChunk> flux = unit.stream(invocation,
                ThinkingSettings.legacyDefault(), false).chunks();
        assertThrows(RuntimeException.class, flux::blockLast);
    }
}
