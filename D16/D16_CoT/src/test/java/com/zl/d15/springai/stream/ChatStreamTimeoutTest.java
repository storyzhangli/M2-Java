package com.zl.d15.springai.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d16.service.AiChatService;
import com.zl.d16.service.ChatInvocation;
import com.zl.d16.service.StreamChunk;
import com.zl.d16.service.ThinkingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import reactor.core.publisher.Flux;

/**
 * SSE 服务端超时专项测试（验收整改项）。
 *
 * <p>通过测试属性把 {@code app.ai.stream-timeout-ms} 压缩到 150ms，并让 Mock 服务返回
 * 一个永不结束的 {@link Flux}，验证三件事：</p>
 * <ol>
 *     <li>超时后客户端确实能收到 {@code error} 事件（code=5000、超时文案），
 *     而不是被 finished 终态标记拦截——这是本次修复的核心回归点；</li>
 *     <li>超时触发时上游 Reactor 订阅被 dispose（doOnCancel 闭锁放行），停止无意义的模型消费；</li>
 *     <li>响应能够正常结束（exchange 正常返回且无 complete 事件），连接完成收尾。</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.ai.stream-timeout-ms=150"
)
class ChatStreamTimeoutTest {

    private static final String STREAM_URL = "/api/v1/ai/chat/stream";

    @MockitoBean
    private AiChatService aiChatService;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void timeoutShouldDeliverErrorEventCancelUpstreamAndFinishConnection() throws Exception {
        CountDownLatch upstreamCanceled = new CountDownLatch(1);
        when(aiChatService.streamChat(anyString())).thenReturn(
                // 永不发数据、永不结束；一旦订阅被 dispose 即放行闭锁，证明上游资源被释放
                Flux.never()
                        .doOnCancel(upstreamCanceled::countDown)
                        .cast(String.class)
        );
        // D16 起控制器走类型化分片新方法；补一个同语义的 never 流并保留取消信号
        when(aiChatService.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.<StreamChunk>never().doOnCancel(upstreamCanceled::countDown));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "超时专项测试")), headers);

        // 阻塞到服务端 complete 为止；若超时分支没有正常收尾，这里会一直挂到客户端读超时
        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL, HttpMethod.POST, entity, byte[].class);

        String body = new String(response.getBody(), StandardCharsets.UTF_8);

        // 1. 协议层：200 + event-stream，先有 start，随后是可被客户端实际收到的超时 error
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");
        assertThat(body).contains("event:start");
        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":5000");
        assertThat(body).contains("流式响应超时");
        // 超时是异常终态，绝不能再下发 complete 伪装成功
        assertThat(body).doesNotContain("event:complete");

        // 2. 上游订阅必须被取消（留足容器调度余量，正常 150ms 超时后即刻触发）
        assertTrue(upstreamCanceled.await(10, TimeUnit.SECONDS),
                "超时后上游 Flux 订阅应被 dispose，doOnCancel 未触发说明存在资源泄漏");
    }
}
