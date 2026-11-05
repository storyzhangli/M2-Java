package com.zl.d23;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 事件顺序与汇总计数回归测试（D23-R02）。
 *
 * <p>真机 RANDOM_PORT + JDK HttpClient 读取原始 SSE：验证事件顺序、delta seq 严格
 * 单调、complete 汇总与 delta 计数/字符数一致、思考关闭时无 thinking 事件。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SseEventOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    private record Event(String name, String data) {
    }

    @Test
    @DisplayName("R02 事件顺序：start 开头、complete 结尾，中间 delta 连续且无 thinking")
    void event_orderAndDeltaSequence() throws Exception {
        List<Event> events = readStream("{\"message\":\"设备无法开机，请给出排查步骤\"}");
        List<String> names = events.stream().map(Event::name).toList();

        assertThat(names).startsWith("start");
        assertThat(names).endsWith("complete");
        assertThat(names).doesNotContain("error", "thinking");

        // 提取所有 delta 并校验 seq 严格从 0 递增
        List<JsonNode> deltas = events.stream()
                .filter(e -> "delta".equals(e.name()))
                .map(e -> read(e.data()))
                .toList();
        assertThat(deltas).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < deltas.size(); i++) {
            assertThat(deltas.get(i).get("seq").asInt()).isEqualTo(i);
        }

        JsonNode complete = events.stream()
                .filter(e -> "complete".equals(e.name()))
                .findFirst().map(e -> read(e.data())).orElseThrow();

        // complete.seq 等于 delta 数量
        assertThat(complete.get("seq").asInt()).isEqualTo(deltas.size());

        // totalChars 等于各 delta 文本长度之和
        int sum = deltas.stream().mapToInt(d -> d.get("content").asText().length()).sum();
        assertThat(complete.get("totalChars").asInt()).isEqualTo(sum);

        assertThat(complete.get("mode").asText()).isEqualTo("mock");
        assertThat(complete.get("model").asText()).isEqualTo("offline-mock");
        assertThat(complete.get("mocked").asBoolean()).isTrue();

        // complete 之后不存在任何内容事件
        int completeIndex = names.indexOf("complete");
        assertThat(completeIndex).isEqualTo(names.size() - 1);
    }

    @Test
    @DisplayName("R02 start 事件回传非空 sessionId 且模式字段正确")
    void start_payloadCarriesSession() throws Exception {
        List<Event> events = readStream("{\"message\":\"你好\"}");

        JsonNode start = read(events.get(0).data());

        assertThat(events.get(0).name()).isEqualTo("start");
        assertThat(start.get("sessionId").asText()).isNotBlank();
        assertThat(start.get("mode").asText()).isEqualTo("mock");
        assertThat(start.get("model").asText()).isEqualTo("offline-mock");
    }

    @Test
    @DisplayName("R02 delta 文本按序拼合为非空完整回复")
    void deltas_joinToNonBlankReply() throws Exception {
        List<Event> events = readStream("{\"message\":\"主轴异响\"}");

        String joined = events.stream()
                .filter(e -> "delta".equals(e.name()))
                .map(e -> read(e.data()).get("content").asText())
                .reduce("", String::concat);

        assertThat(joined).isNotBlank();
    }

    private JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Event> readStream(String jsonBody) throws Exception {
        HttpResponse<Stream<String>> response = CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        return parseSse(response.body().toList());
    }

    private List<Event> parseSse(List<String> lines) {
        List<Event> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                if (name != null || data.length() > 0) {
                    events.add(new Event(name == null ? "message" : name, data.toString()));
                }
                name = null;
                data = new StringBuilder();
            } else if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append("\n");
                }
                data.append(line.substring(5).trim());
            }
        }
        return events;
    }
}
