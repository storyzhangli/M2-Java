package com.zl.d15.springai.d16;

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
 * D16 引用来源同步接口集成测试：真实检索命中、无命中不伪造、结构化字段可回溯、请求级隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CitationIntegrationTest {

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
    void retrievalEnabledShouldReturnOnlyRealCitationsWithInlineMarkers() throws Exception {
        JsonNode data = post(Map.of(
                "message", "数控机床主轴过热报警，冷却液液位正常但还是报警怎么办",
                "retrieveEnabled", true
        )).path("data");

        JsonNode citations = data.path("citations");
        assertThat(citations.isArray()).isTrue();
        assertThat(citations.size()).isGreaterThan(0);
        for (JsonNode citation : citations) {
            assertThat(citation.path("id").asText()).startsWith("S");
            assertThat(citation.path("documentId").asText()).startsWith("DOC-");
            assertThat(citation.path("chunkId").asText()).contains("-C");
            assertThat(citation.path("title").asText()).isNotBlank();
            assertThat(citation.path("excerpt").asText()).isNotBlank();
            // 禁止暴露本机绝对路径，无链接时 url 字段隐藏
            assertThat(citation.has("url")).isFalse();
        }
        // 答案内联标记必须能在结构化列表中找到映射
        String content = data.path("content").asText();
        assertThat(content).contains("[S1]");
    }

    @Test
    void retrievalDisabledShouldReturnEmptyCitations() throws Exception {
        JsonNode data = post(Map.of(
                "message", "数控机床主轴过热报警",
                "retrieveEnabled", false
        )).path("data");
        assertThat(data.path("citations").size()).isZero();
        assertThat(data.path("content").asText()).doesNotContain("[S");
    }

    @Test
    void noHitQueryMustNotFabricateCitations() throws Exception {
        JsonNode data = post(Map.of(
                "message", "请问周末去哪玩比较合适",
                "retrieveEnabled", true
        )).path("data");
        assertThat(data.path("citations").size()).isZero();
        assertThat(data.path("content").asText()).doesNotContain("[S");
    }

    @Test
    void citationNumberingIsRequestScopedAndRestartsEachRequest() throws Exception {
        Map<String, Object> body = Map.of(
                "message", "伺服电机编码器位置偏差报警怎么处理",
                "retrieveEnabled", true
        );
        JsonNode first = post(body).path("data");
        JsonNode second = post(body).path("data");

        assertThat(first.path("citations").get(0).path("id").asText()).isEqualTo("S1");
        assertThat(second.path("citations").get(0).path("id").asText()).isEqualTo("S1");
        // 两次响应内容独立，不携带上一轮状态
        assertThat(first.path("citations").size()).isEqualTo(second.path("citations").size());
    }
}
