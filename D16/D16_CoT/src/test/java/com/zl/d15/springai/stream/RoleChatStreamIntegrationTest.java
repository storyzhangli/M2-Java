package com.zl.d15.springai.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d15.springai.support.AbstractPersistenceCleanupTest;
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
 * 角色链路 SSE 真机集成测试（随机端口 Tomcat + mock 模型，无外网依赖）：
 * 验证 start 帧携带会话/模板溯源、delta/complete 顺序、第二轮历史可见以及模板管理静态页可访问。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleChatStreamIntegrationTest extends AbstractPersistenceCleanupTest {

    private static final String STREAM_URL = "/api/v1/ai/chat/stream";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roleStreamStartFrameShouldCarrySessionAndTemplateMetadata() throws Exception {
        HttpEntity<String> entity = jsonEntity(Map.of(
                "message", "流式角色提问：如何申请退货",
                "scene", "customer_service"));

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL, HttpMethod.POST, entity, byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");

        List<SseFrame> frames = parseFrames(new String(response.getBody(), StandardCharsets.UTF_8));
        JsonNode start = frames.stream().filter(f -> "start".equals(f.name())).findFirst().orElseThrow().data;
        assertThat(start.path("sessionId").asText()).hasSize(32);
        assertThat(start.path("scene").asText()).isEqualTo("customer_service");
        assertThat(start.path("templateId").asText()).isEqualTo("customer_service");
        assertThat(start.path("templateVersion").asInt()).isEqualTo(1);

        List<String> names = frames.stream().map(f -> f.name).toList();
        assertThat(names).startsWith("start").endsWith("complete");
        String joined = frames.stream().filter(f -> "delta".equals(f.name()))
                .map(f -> f.data.path("content").asText())
                .reduce("", String::concat);
        assertThat(joined).contains("历史消息条数=0").contains("在线客服代表");
    }

    @Test
    void secondRoleStreamTurnWithSessionShouldSeeHistory() throws Exception {
        ResponseEntity<byte[]> first = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of("message", "第一轮流式", "scene", "programming")), byte[].class);
        List<SseFrame> firstFrames = parseFrames(new String(first.getBody(), StandardCharsets.UTF_8));
        String sessionId = firstFrames.stream().filter(f -> "start".equals(f.name()))
                .findFirst().orElseThrow().data.path("sessionId").asText();

        ResponseEntity<byte[]> second = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of(
                        "message", "第二轮流式",
                        "scene", "programming",
                        "sessionId", sessionId)), byte[].class);
        List<SseFrame> secondFrames = parseFrames(new String(second.getBody(), StandardCharsets.UTF_8));
        String joined = secondFrames.stream().filter(f -> "delta".equals(f.name()))
                .map(f -> f.data.path("content").asText())
                .reduce("", String::concat);
        assertThat(joined).contains("历史消息条数=2");
    }

    @Test
    void illegalRoleShouldFallBackToJsonBeforeStreamStarts() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of("message", "非法角色", "scene", "ghost_role")), byte[].class);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        JsonNode json = objectMapper.readTree(new String(response.getBody(), StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt()).isEqualTo(4001);
    }

    @Test
    void templateAdminPageShouldBeServed() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/templates.html", byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String html = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(html).contains("Prompt 模板管理").contains("/js/templates.js");
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> payload) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
    }

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
