package com.zl.d23;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d23.dto.ChatReplyResponse;
import com.zl.d23.history.ChatHistoryService;
import com.zl.d23.history.model.MessageStatus;
import com.zl.d23.service.AiChatResult;
import com.zl.d23.service.AiChatService;
import com.zl.d23.service.ChatInvocation;
import com.zl.d23.service.StreamChunk;
import com.zl.d23.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 失败生命周期回归测试（D23-R01/R02）。
 *
 * <p>通过 {@link Primary} 可控 AiChatService 替身，在不触网、不依赖真实 Key 的前提下验证：
 * 同步异常落 FAILED 并返回系统错误码；SSE 上游异常下发 error 命名事件且不伪造 complete；
 * 应用层超时（stream-timeout-ms=300）下发超时 error 事件。断言真实落库状态与事件序列。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.ai.stream-timeout-ms=300")
@Import(AiFailureLifecycleTest.ControllableConfig.class)
class AiFailureLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ControllableAiChatService controllable;

    @Autowired
    private ChatHistoryService historyService;

    private record Event(String name, String data) {
    }

    @BeforeEach
    void reset() {
        controllable.reset();
    }

    @Test
    @DisplayName("R01 同步异常：返回系统错误码且用户消息落 FAILED")
    void syncException_returnsErrorCodeAndPersistsFailed() throws Exception {
        controllable.failSync(new RuntimeException("boom"));
        String owner = "ut_fail_sync";

        HttpResponse<String> response = CLIENT.send(
                syncRequest(owner, "{\"message\":\"触发同步失败\"}"),
                HttpResponse.BodyHandlers.ofString());

        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("code").asInt()).isEqualTo(5000);
        assertThat(result.get("data").isNull()).isTrue();

        awaitFailedPersisted(owner);
    }

    @Test
    @DisplayName("R02 上游异常：start+delta 后下发 error 且无 complete，落 FAILED")
    void streamError_emitsErrorEventAndFails() throws Exception {
        controllable.useStream(Flux.concat(
                Flux.just(new StreamChunk.ContentChunk("部分回答")),
                Flux.error(new RuntimeException("upstream boom"))));
        String owner = "ut_fail_stream";

        List<Event> events = readStream(owner, "{\"message\":\"触发流式异常\"}");
        List<String> names = events.stream().map(Event::name).toList();

        assertThat(names).startsWith("start");
        assertThat(names).contains("delta");
        assertThat(names).endsWith("error");
        assertThat(names).doesNotContain("complete");

        JsonNode error = MAPPER.readTree(events.get(events.size() - 1).data());
        assertThat(error.get("code").asInt()).isEqualTo(5000);

        awaitFailedPersisted(owner);
    }

    @Test
    @DisplayName("R02 应用层超时：无 delta 直接下发超时 error，落 FAILED")
    void streamTimeout_emitsTimeoutError() throws Exception {
        // 永不发射，配合 stream-timeout-ms=300 触发应用层 TimeoutException
        controllable.useStream(Flux.never());
        String owner = "ut_fail_timeout";

        List<Event> events = readStream(owner, "{\"message\":\"触发流式超时\"}");
        List<String> names = events.stream().map(Event::name).toList();

        assertThat(names).containsExactly("start", "error");

        JsonNode error = MAPPER.readTree(events.get(1).data());
        assertThat(error.get("code").asInt()).isEqualTo(5000);
        assertThat(error.get("message").asText()).contains("超时");

        awaitFailedPersisted(owner);
    }

    private void awaitFailedPersisted(String owner) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<com.zl.d23.history.model.ChatSession> sessions =
                            historyService.pageSessions(owner, 1, 10).records();
                    assertThat(sessions).isNotEmpty();
                    String sid = sessions.get(0).getSessionId();
                    var messages = historyService.pageMessages(owner, sid, 1, 50).records();
                    assertThat(messages).anyMatch(m -> m.getStatus() == MessageStatus.FAILED);
                    assertThat(messages).noneMatch(m -> m.getStatus() == MessageStatus.COMPLETED);
                });
    }

    private HttpRequest syncRequest(String owner, String jsonBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat"))
                .header("Content-Type", "application/json")
                .header("X-Dev-User", owner)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private List<Event> readStream(String owner, String jsonBody) throws Exception {
        HttpResponse<Stream<String>> response = CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("X-Dev-User", owner)
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

    /**
     * 可控 AiChatService 替身：可注入同步异常或任意 Flux，不发起任何网络请求。
     */
    static class ControllableAiChatService implements AiChatService {

        private final AtomicReference<RuntimeException> syncError = new AtomicReference<>();

        private final AtomicReference<Flux<StreamChunk>> stream = new AtomicReference<>();

        void reset() {
            syncError.set(null);
            stream.set(null);
        }

        void failSync(RuntimeException error) {
            syncError.set(error);
        }

        void useStream(Flux<StreamChunk> flux) {
            stream.set(flux);
        }

        @Override
        public ChatReplyResponse chat(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<String> streamChat(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiChatResult chat(ChatInvocation invocation, ThinkingSettings settings) {
            RuntimeException error = syncError.get();
            if (error != null) {
                throw error;
            }
            return new AiChatResult("mock", "offline-mock", true, false, "fallback", null);
        }

        @Override
        public Flux<StreamChunk> streamChat(ChatInvocation invocation, ThinkingSettings settings) {
            Flux<StreamChunk> flux = stream.get();
            return flux != null ? flux : Flux.empty();
        }
    }

    @TestConfiguration
    static class ControllableConfig {

        @Bean
        @Primary
        ControllableAiChatService controllableAiChatService() {
            return new ControllableAiChatService();
        }
    }
}
