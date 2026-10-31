package com.zl.d17.springai.d16;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D16 思考模式同步接口集成测试（默认 mock 模式，零外网）。
 * 验证开关缺省兼容、推理/答案字段分离、受控展示开关以及服务端参数拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThinkingModeIntegrationTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode post(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(body);
        String response = restTemplate.postForObject(
                CHAT_URL, new HttpEntity<>(json, headers), String.class);
        return objectMapper.readTree(response);
    }

    @Test
    void legacyRequestWithoutThinkingFieldsShouldKeepOldShape() throws Exception {
        JsonNode data = post(Map.of("message", "旧链路兼容性验证")).path("data");
        assertThat(data.path("mode").asText()).isEqualTo("mock");
        // 未启用思考：推理字段不出现，思考开关为 null 被 NON_NULL 隐藏，引用为空列表
        assertThat(data.has("reasoningContent")).isFalse();
        assertThat(data.has("thinkingEnabled")).isFalse();
        assertThat(data.path("citations").isArray()).isTrue();
        assertThat(data.path("citations").size()).isZero();
        assertThat(data.path("content").asText()).contains("[MOCK:D01]");
    }

    @Test
    void thinkingEnabledWithShowReasoningShouldSeparateReasoningFromContent() throws Exception {
        JsonNode data = post(Map.of(
                "message", "思考模式全开",
                "thinkingEnabled", true,
                "showReasoning", true
        )).path("data");

        assertThat(data.path("thinkingEnabled").asBoolean()).isTrue();
        assertThat(data.path("reasoningContent").asText()).contains("[MOCK-REASONING:D16]");
        // 推理文本不得混入最终答案
        assertThat(data.path("content").asText())
                .contains("[MOCK:D01]")
                .doesNotContain("[MOCK-REASONING:D16]");
    }

    @Test
    void thinkingEnabledButHiddenReasoningShouldNotExposeReasoningField() throws Exception {
        JsonNode data = post(Map.of(
                "message", "只开思考不展示过程",
                "thinkingEnabled", true
        )).path("data");

        assertThat(data.path("thinkingEnabled").asBoolean()).isTrue();
        assertThat(data.has("reasoningContent")).isFalse();
        assertThat(data.path("content").asText()).doesNotContain("[MOCK-REASONING:D16]");
    }

    @Test
    void showReasoningAloneWithoutThinkingShouldBeIgnored() throws Exception {
        JsonNode data = post(Map.of(
                "message", "只勾展示不勾思考",
                "showReasoning", true
        )).path("data");
        assertThat(data.has("reasoningContent")).isFalse();
        assertThat(data.has("thinkingEnabled")).isFalse();
    }

    @Test
    void illegalEffortShouldReturn4001() throws Exception {
        JsonNode root = post(Map.of(
                "message", "非法强度",
                "thinkingEnabled", true,
                "reasoningEffort", "ultra"
        ));
        assertThat(root.path("code").asInt()).isEqualTo(4001);
        assertThat(root.path("msg").asText()).contains("LOW / HIGH / MAX");
    }

    @Test
    void unsupportedTransmittableEffortShouldBeRejectedInsteadOfSilentlyDropped() throws Exception {
        JsonNode root = post(Map.of(
                "message", "显式强度但客户端不支持",
                "thinkingEnabled", true,
                "reasoningEffort", "HIGH"
        ));
        assertThat(root.path("code").asInt()).isEqualTo(4001);
        assertThat(root.path("msg").asText()).contains("不支持下发");
    }

    @Test
    void effortWithoutEnablingThinkingShouldReturn4001() throws Exception {
        JsonNode root = post(Map.of(
                "message", "未开思考先给强度",
                "reasoningEffort", "LOW"
        ));
        assertThat(root.path("code").asInt()).isEqualTo(4001);
    }
}
