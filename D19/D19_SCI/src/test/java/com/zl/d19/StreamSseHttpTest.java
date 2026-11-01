package com.zl.d19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d19.history.ChatHistoryService;
import com.zl.d19.history.model.HistoryMessage;
import com.zl.d19.history.model.MessageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 流式 SSE 真机 HTTP 测试（T03/T06）。
 *
 * <p>使用 JDK 内置 {@link HttpClient} 直连嵌入式 Tomcat，读取原始 SSE 字节流，
 * 验证真分段（多个独立 delta 事件）与客户端中途断开后的中断语义。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StreamSseHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ChatHistoryService historyService;

    private record Event(String name, String data) {
    }

    @Test
    @DisplayName("T03 真机 SSE 真分段：start + 多个 delta + complete")
    void sse_realStreamMultipleChunks() throws Exception {
        HttpResponse<Stream<String>> response = CLIENT.send(
                newRequest("{\"message\":\"设备无法开机，请帮我排查\"}"),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("text/event-stream");
        assertThat(response.headers().firstValue("x-accel-buffering")).hasValue("no");

        List<Event> events = parseSse(response.body().toList());
        List<String> names = events.stream().map(Event::name).toList();

        assertThat(names).startsWith("start");
        assertThat(names).endsWith("complete");
        long deltaCount = names.stream().filter("delta"::equals).count();
        assertThat(deltaCount).isGreaterThanOrEqualTo(2);

        // start 事件回传 sessionId
        JsonNode startNode = MAPPER.readTree(events.get(0).data());
        assertThat(startNode.get("sessionId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("T06 客户端中途断开后服务端保留 INTERRUPTED 消息")
    void clientDisconnect_persistsInterrupted() throws Exception {
        HttpResponse<Stream<String>> response = CLIENT.send(
                newRequest("{\"message\":\"设备无法开机，请帮我排查并给出详细步骤\"}"),
                HttpResponse.BodyHandlers.ofLines());

        Stream<String> lines = response.body();
        Iterator<String> it = lines.iterator();
        List<String> consumed = new ArrayList<>();
        String sessionId = null;
        boolean sawDelta = false;
        while (it.hasNext()) {
            String line = it.next();
            consumed.add(line);
            if (line.startsWith("data:") && sessionId == null) {
                String payload = line.substring(5).trim();
                if (payload.contains("sessionId")) {
                    sessionId = MAPPER.readTree(payload).get("sessionId").asText();
                }
            }
            if (line.equals("event:delta")) {
                sawDelta = true;
            }
            if (sawDelta && sessionId != null) {
                break;
            }
        }
        final String sid = sessionId;
        assertThat(sid).isNotBlank();
        // 关闭流即取消底层 HTTP 请求，模拟客户端断开
        lines.close();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<HistoryMessage> msgs = historyService.pageMessages(
                            "ut-default", sid, 1, 50).records();
                    boolean interrupted = msgs.stream()
                            .anyMatch(m -> m.getStatus() == MessageStatus.INTERRUPTED);
                    assertThat(interrupted).isTrue();
                });
    }

    private HttpRequest newRequest(String jsonBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    /**
     * 按 SSE 空行分块解析事件，兼容 event: 与 data: 两类字段。
     */
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
