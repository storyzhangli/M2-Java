package com.zl.d15.springai.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Tomcat（随机端口）下的 SSE 流式接口集成测试，默认 mock 模式，无需外网与密钥。
 * 验证响应头、SSE 帧顺序、UTF-8 中文、结束事件、参数错误的 JSON 回落以及静态页面可访问。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamIntegrationTest {

    private static final String STREAM_URL = "/api/v1/ai/chat";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamShouldReturnOrderedSseFramesWithUtf8ContentAndCompleteEvent() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "SSE流式联调测试")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL + "/stream", HttpMethod.POST, entity, byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        List<SseFrame> frames = parseFrames(body);

        List<String> eventNames = frames.stream().map(SseFrame::name).toList();
        assertThat(eventNames).startsWith("start");
        assertThat(eventNames).contains("delta");
        assertThat(eventNames).endsWith("complete");
        assertThat(eventNames).doesNotContain("error");

        // delta 的 seq 必须从 0 连续递增，保证前端可按序追加
        List<Integer> seqs = frames.stream()
                .filter(f -> "delta".equals(f.name))
                .map(f -> f.data.path("seq").asInt())
                .toList();
        assertThat(seqs).isNotEmpty();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i);
        }

        String joinedDelta = frames.stream()
                .filter(f -> "delta".equals(f.name))
                .map(f -> f.data.path("content").asText())
                .reduce("", String::concat);
        // UTF-8 中文正确、模拟标记存在，且完成事件统计长度与实际增量一致
        assertThat(joinedDelta).contains("[MOCK:D01]").contains("SSE流式联调测试");
        JsonNode complete = frames.stream().filter(f -> "complete".equals(f.name)).findFirst().orElseThrow().data;
        assertThat(complete.path("totalChars").asInt()).isEqualTo(joinedDelta.length());
        assertThat(complete.path("mocked").asBoolean()).isTrue();
    }

    @Test
    void blankMessageShouldFallBackToJsonValidationErrorInsteadOfSse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "   ")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL + "/stream", HttpMethod.POST, entity, byte[].class);

        // 流尚未开始，走全局异常处理器，返回统一 JSON 而非 event-stream
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        JsonNode json = objectMapper.readTree(new String(response.getBody(), StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt()).isEqualTo(4001);
    }

    @Test
    void indexPageShouldBeServedAsStaticResource() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/", byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String html = new String(response.getBody(), StandardCharsets.UTF_8);
        // D15 迭代后页面标题更新为双角色对话，静态资源引用保持不变
        assertThat(html).contains("D15 双角色流式对话").contains("/js/app.js").contains("/css/app.css");
    }

    /** 极简 SSE 帧解析：按空行分块，提取 event 名称与 JSON data。 */
    private List<SseFrame> parseFrames(String body) throws Exception {
        List<SseFrame> frames = new ArrayList<>();
        String[] blocks = body.split("\\r?\\n\\r?\\n");
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            String name = "message";
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    String part = line.substring("data:".length());
                    data.append(part.startsWith(" ") ? part.substring(1) : part);
                }
            }
            if (data.length() > 0) {
                frames.add(new SseFrame(name, objectMapper.readTree(data.toString())));
            }
        }
        return frames;
    }

    private record SseFrame(String name, JsonNode data) {
    }
}
