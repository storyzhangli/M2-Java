package com.zl.d01.springai.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Servlet 容器（随机端口）集成测试。
 *
 * <p>MockMvc 不执行完整的容器错误页派发，错误语义与生产 Tomcat 存在差异；本类启动真实 Tomcat，
 * 用 {@link TestRestTemplate} 走真实 Socket，验证：</p>
 * <ul>
 *     <li>未匹配路径在本项目“HTTP 恒 200、body.code 区分错误”契约下的真实表现，且访问日志在 HTTP 层有记录（无 Controller 切面事件）；</li>
 *     <li>{@code ContentCachingResponseWrapper#copyBodyToResponse()} 正确写回，正常接口客户端能收到完整正文。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLogRealContainerTest {

    @Autowired
    private TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;
    private Logger accessLogger;

    @BeforeEach
    void setUp() {
        // 禁止对 4xx/5xx 抛异常，便于直接断言状态码
        restTemplate.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
        accessLogger = (Logger) LoggerFactory.getLogger(CachedBodyAccessLogFilter.ACCESS_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void unmatchedPathShouldBeCaughtByGlobalFallbackButStillLoggedAtHttpLayer() {
        // 现状边界（经真实 Tomcat 验证）：Boot 3 对未匹配路径抛 NoResourceFoundException，
        // 但本项目 GlobalExceptionHandler 的兜底 Exception 处理器将其归一为 HTTP 200 + body code 5000，
        // 而非容器默认 404。本次迭代保持该统一返回契约不变，仅验证过滤器仍能在 HTTP 层完整记录此类请求。
        ResponseEntity<String> response =
                restTemplate.getForEntity("/no-such-endpoint-real", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":5000");
        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();

        List<JsonNode> events = eventsFor(requestId);
        List<String> types = events.stream().map(n -> n.path("event").asText()).toList();
        // 未进入 controller 包内任何方法：只有过滤器 HTTP 事件，没有 AOP 切面事件
        assertThat(types).contains("HTTP_REQUEST", "HTTP_RESPONSE");
        assertThat(types).doesNotContain("CONTROLLER_ACCESS");
        assertThat(events.stream()
                .filter(n -> "HTTP_RESPONSE".equals(n.path("event").asText()))
                .findFirst().orElseThrow().path("httpStatus").asInt()).isEqualTo(200);
    }

    @Test
    void normalChatOverRealSocketShouldReturnFullBodyProvingResponseCopyBack() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer real-socket-secret");
        String body = objectMapper.writeValueAsString(Map.of("message", "真实容器响应写回测试"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/ai/chat", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":200").contains("[MOCK:D01]");
        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();

        List<JsonNode> events = eventsFor(requestId);
        assertThat(events.stream().map(n -> n.path("event").asText()).toList())
                .contains("HTTP_REQUEST", "HTTP_RESPONSE", "CONTROLLER_ACCESS");

        String allLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(allLogs).contains(requestId).doesNotContain("real-socket-secret");
    }

    private List<JsonNode> eventsFor(String requestId) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains(requestId))
                .map(this::parseQuietly)
                .filter(n -> n != null)
                .toList();
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }
}
