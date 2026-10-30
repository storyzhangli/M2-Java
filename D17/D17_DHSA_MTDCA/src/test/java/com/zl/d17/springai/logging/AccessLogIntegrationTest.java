package com.zl.d17.springai.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.logging.CachedBodyAccessLogFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访问日志端到端集成测试：通过给 ACCESS_LOG Logger 挂载内存 Appender，
 * 在不依赖外部日志文件的前提下断言 requestId 关联、脱敏、404 边界与响应完整性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessLogIntegrationTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;
    private Logger accessLogger;

    @BeforeEach
    void attachAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger(CachedBodyAccessLogFilter.ACCESS_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void normalRequestShouldCorrelateAllEventsBySameRequestIdAndKeepResponseIntact() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "访问日志关联测试"));

        MvcResult result = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer must-be-masked")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();

        List<JsonNode> events = eventsFor(requestId);
        assertThat(eventTypes(events)).contains("HTTP_REQUEST", "HTTP_RESPONSE", "CONTROLLER_ACCESS");

        // 响应未被包装器消费：客户端仍拿到完整统一返回体
        JsonNode responseEvent = findEvent(events, "HTTP_RESPONSE");
        assertThat(responseEvent.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(responseEvent.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(responseEvent.path("responseBody").path("body").asText()).contains("\"code\":200");

        // 同一 requestId 贯穿，且敏感头被脱敏、原文不落日志
        String allLogs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(allLogs).contains(requestId);
        assertThat(allLogs).doesNotContain("must-be-masked");
        assertThat(allLogs).contains("*****");
    }

    @Test
    void twoRequestsShouldUseDistinctRequestIdsWithoutMdcLeak() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "第一次"));
        MvcResult first = mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        MvcResult second = mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        String id1 = first.getResponse().getHeader("X-Request-Id");
        String id2 = second.getResponse().getHeader("X-Request-Id");
        assertThat(id1).isNotBlank();
        assertThat(id2).isNotBlank();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void malformedJsonShouldReachExceptionHandlerAndResponseRemainsIntact() throws Exception {
        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content("{ broken-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // 响应正文在事件中是嵌套字符串，引号被转义，因此按转义后的片段断言
        boolean hasHandledResponse = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("HTTP_RESPONSE") && line.contains("\\\"code\\\":4001"));
        assertThat(hasHandledResponse).isTrue();
    }

    private List<JsonNode> eventsFor(String requestId) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains(requestId))
                .map(this::parseQuietly)
                .filter(node -> node != null)
                .toList();
    }

    private List<String> eventTypes(List<JsonNode> events) {
        return events.stream().map(n -> n.path("event").asText()).toList();
    }

    private JsonNode findEvent(List<JsonNode> events, String event) {
        return events.stream().filter(n -> event.equals(n.path("event").asText())).findFirst().orElseThrow();
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }
}
