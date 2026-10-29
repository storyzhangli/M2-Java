package com.zl.d15.springai.d16;

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
 * D16 SSE 新事件协议集成测试：thinking 事件受控下发、citations 先于 complete、
 * 关闭展示时推理不下发、请求级引用编号在流上独立。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class D16ThinkingStreamIntegrationTest {

    private static final String STREAM_URL = "/api/v1/ai/chat/stream";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<Frame> stream(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                STREAM_URL, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return parseFrames(new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void thinkingAndCitationsEventsShouldFollowProtocolOrder() throws Exception {
        List<Frame> frames = stream(Map.of(
                "message", "数控机床主轴过热报警冷却液液位怎么处理",
                "thinkingEnabled", true,
                "showReasoning", true,
                "retrieveEnabled", true
        ));
        List<String> names = frames.stream().map(f -> f.name).toList();

        assertThat(names).startsWith("start");
        assertThat(names).contains("thinking", "delta", "citations");
        // 终态必须是 complete，citations 在它之前
        assertThat(names).endsWith("complete");
        assertThat(names.indexOf("citations")).isLessThan(names.indexOf("complete"));
        // 所有 thinking 都必须出现在首个 delta 之前（mock 实现先推理后答案，协议上也禁止混流）
        int firstDelta = names.indexOf("delta");
        assertThat(names.lastIndexOf("thinking")).isLessThan(firstDelta);

        JsonNode start = frames.get(0).data;
        assertThat(start.path("thinkingEnabled").asBoolean()).isTrue();

        String thinkingText = frames.stream().filter(f -> f.name.equals("thinking"))
                .map(f -> f.data.path("content").asText()).reduce("", String::concat);
        String answerText = frames.stream().filter(f -> f.name.equals("delta"))
                .map(f -> f.data.path("content").asText()).reduce("", String::concat);
        assertThat(thinkingText).contains("[MOCK-REASONING:D16]");
        assertThat(answerText).doesNotContain("[MOCK-REASONING:D16]");

        JsonNode citations = frames.stream().filter(f -> f.name.equals("citations"))
                .findFirst().orElseThrow().data.path("citations");
        assertThat(citations.size()).isGreaterThan(0);
        assertThat(citations.get(0).path("id").asText()).isEqualTo("S1");
    }

    @Test
    void hiddenReasoningShouldProduceNoThinkingEvents() throws Exception {
        List<Frame> frames = stream(Map.of(
                "message", "思考但不展示过程",
                "thinkingEnabled", true
        ));
        List<String> names = frames.stream().map(f -> f.name).toList();
        assertThat(names).doesNotContain("thinking");
        assertThat(names).startsWith("start").endsWith("complete");
        assertThat(frames.get(0).data.path("thinkingEnabled").asBoolean()).isTrue();
    }

    @Test
    void defaultRequestShouldKeepLegacyFrameSet() throws Exception {
        List<Frame> frames = stream(Map.of("message", "旧帧协议回归"));
        List<String> names = frames.stream().map(f -> f.name).toList();
        assertThat(names).startsWith("start").endsWith("complete");
        assertThat(names).doesNotContain("thinking", "citations");
        // 旧客户端不感知思考字段
        assertThat(frames.get(0).data.has("thinkingEnabled")).isFalse();
    }

    @Test
    void twoStreamRequestsShouldKeepIndependentCitationNumbering() throws Exception {
        Map<String, Object> body = Map.of(
                "message", "冷却泵异响流量不足怎么排查",
                "retrieveEnabled", true
        );
        List<Frame> first = stream(body);
        List<Frame> second = stream(body);

        String firstFirstId = first.stream().filter(f -> f.name.equals("citations")).findFirst()
                .orElseThrow().data.path("citations").get(0).path("id").asText();
        String secondFirstId = second.stream().filter(f -> f.name.equals("citations")).findFirst()
                .orElseThrow().data.path("citations").get(0).path("id").asText();
        assertThat(firstFirstId).isEqualTo("S1");
        assertThat(secondFirstId).isEqualTo("S1");
    }

    private List<Frame> parseFrames(String body) throws Exception {
        List<Frame> frames = new ArrayList<>();
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
                frames.add(new Frame(name, objectMapper.readTree(data.toString())));
            }
        }
        return frames;
    }

    private record Frame(String name, JsonNode data) {
    }
}
