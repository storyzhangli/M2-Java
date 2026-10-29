package com.zl.d15.springai.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d16.service.AiChatService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

/**
 * 流开始后发生异常的路径测试：用 Mockito Bean 替换真实服务，令其流式方法直接发布错误，
 * 验证 Controller 在响应头已提交后下发 SSE {@code error} 事件且不产生 {@code complete}，
 * 失败不会被伪装成正常完成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamErrorEventTest {

    private static final String STREAM_URL = "/api/v1/ai/chat/stream";

    @MockitoBean
    private AiChatService aiChatService;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorAfterStreamStartedShouldEmitErrorEventWithoutComplete() throws Exception {
        when(aiChatService.streamChat(anyString()))
                .thenReturn(Flux.error(new RuntimeException("simulated upstream failure")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "触发流异常")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL, HttpMethod.POST, entity, byte[].class);

        // SSE 响应头已经发出，HTTP 层仍为 200，错误通过协议内事件表达
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":5000");
        assertThat(body).doesNotContain("event:complete");
        // 原始异常细节不得外泄到客户端
        assertThat(body).doesNotContain("simulated upstream failure");
    }
}
