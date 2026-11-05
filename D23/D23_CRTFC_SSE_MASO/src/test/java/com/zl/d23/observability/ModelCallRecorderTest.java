package com.zl.d23.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型调用记录器与观测上下文测试。
 *
 * <p>通过向 MODEL_CALL logger 挂载 Logback ListAppender，直接断言真实输出的单行 JSON，
 * 覆盖同步/流式的成功、异常、取消、首 Chunk 延迟与流式 usage 唯一来源等场景。
 * 不启动 Spring 上下文、不触外网。</p>
 */
class ModelCallRecorderTest {

    private final ObjectMapper json = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;

    private Logger callLogger;

    @BeforeEach
    void setUp() {
        callLogger = (Logger) LoggerFactory.getLogger(ModelCallRecorder.CALL_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        callLogger.detachAppender(appender);
        MDC.clear();
    }

    private ModelCallRecorder recorder() {
        // 无 reader：usage 走可移植回退路径，与 mock 模式装配一致
        return new ModelCallRecorder(new ModelObservabilityProperties(), List.of());
    }

    @Test
    @DisplayName("同步成功：只写一条 SUCCESS 记录，关联 MDC requestId，含单调时钟耗时")
    void syncSuccess() throws Exception {
        MDC.put("requestId", "sync-req-1");
        ModelCallRecorder recorder = recorder();
        ModelCallObservation observation =
                recorder.begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_SYNC);
        ChatResponse output = observation.recordSuccess(response("hi", null, "resp-1"));
        assertThat(output).isNotNull();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("outcome")).isEqualTo("SUCCESS");
        assertThat(record.get("callMode")).isEqualTo("SYNC");
        assertThat(record.get("requestId")).isEqualTo("sync-req-1");
        assertThat(record.get("traceId")).isEqualTo("sync-req-1");
        assertThat(record.get("provider")).isEqualTo("deepseek");
        assertThat(record.get("model")).isEqualTo("deepseek-flash");
        assertThat(record.get("responseId")).isEqualTo("resp-1");
        assertThat(record.get("durationMs")).isNotNull();
        assertThat(record.get("usageStatus")).isEqualTo("NOT_RETURNED");
    }

    @Test
    @DisplayName("同步异常：写一条 ERROR 记录，错误码为异常类名，不回显异常消息")
    void syncError() throws Exception {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_SYNC);
        observation.recordError(new IllegalStateException("secret-detail-in-message"));

        Map<String, Object> record = singleRecord();
        assertThat(record.get("outcome")).isEqualTo("ERROR");
        assertThat(record.get("errorCode")).isEqualTo("IllegalStateException");
        assertThat(record.get("usageStatus")).isEqualTo("NOT_RETURNED");
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("secret-detail-in-message");
    }

    @Test
    @DisplayName("流式正常完成：只写一条 SUCCESS 记录，含首 Chunk 延迟")
    void streamComplete() throws Exception {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        List<ChatResponse> chunks = observation.instrument(
                Flux.just(response("a", null, null), response("b", null, "resp-2")))
                .collectList().block();
        assertThat(chunks).hasSize(2);

        Map<String, Object> record = singleRecord();
        assertThat(record.get("outcome")).isEqualTo("SUCCESS");
        assertThat(record.get("callMode")).isEqualTo("STREAM");
        assertThat(record.get("timeToFirstChunkMs")).isNotNull();
        assertThat(record.get("responseId")).isEqualTo("resp-2");
    }

    @Test
    @DisplayName("流式异常：写一条 ERROR 记录，错误透传，终态只写一次")
    void streamError() {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        Flux<ChatResponse> source = Flux.concat(
                Flux.just(response("a", null, null)),
                Flux.error(new RuntimeException("boom")));

        assertThatThrownBy(() -> observation.instrument(source).blockLast())
                .isInstanceOf(RuntimeException.class);

        assertThat(appender.list).hasSize(1);
        Map<String, Object> record = parse(0);
        assertThat(record.get("outcome")).isEqualTo("ERROR");
        assertThat(record.get("errorCode")).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("客户端取消：写一条 CANCELLED 记录")
    void streamCancelled() throws Exception {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        observation.instrument(Flux.just(
                        response("a", null, null),
                        response("b", null, null),
                        response("c", null, null)))
                .take(1)
                .collectList().block();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("outcome")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("流式 usage 仅在最终 chunk：取最终值，不与中间 chunk 相加")
    void streamFinalUsageNotSummed() throws Exception {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        Flux<ChatResponse> source = Flux.just(
                response("a", new DefaultUsage(1, 1, 2), null),
                response("b", new DefaultUsage(3, 4, 7), null),
                response("c", new DefaultUsage(5, 7, 12), "resp-3"));
        observation.instrument(source).blockLast();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("promptTokens")).isEqualTo(5);
        assertThat(record.get("completionTokens")).isEqualTo(7);
        assertThat(record.get("totalTokens")).isEqualTo(12);
        assertThat(record.get("usageStatus")).isEqualTo("AVAILABLE");
        assertThat(record.get("usageSource")).isEqualTo("PROVIDER_METADATA");
    }

    @Test
    @DisplayName("流式所有 chunk 都无 usage：状态 NOT_RETURNED，数值字段为 null")
    void streamNoUsage() throws Exception {
        ModelCallObservation observation = recorder()
                .begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        observation.instrument(Flux.just(
                response("a", null, null), response("b", null, null))).blockLast();

        Map<String, Object> record = singleRecord();
        assertThat(record.get("usageStatus")).isEqualTo("NOT_RETURNED");
        assertThat(record.get("usageSource")).isEqualTo("NONE");
        assertThat(record.get("promptTokens")).isNull();
        assertThat(record.get("totalTokens")).isNull();
    }

    @Test
    @DisplayName("观测关闭时 instrument 原样返回流且不写记录")
    void disabled() {
        ModelObservabilityProperties properties = new ModelObservabilityProperties();
        properties.setEnabled(false);
        ModelCallRecorder recorder = new ModelCallRecorder(properties, List.of());
        ModelCallObservation observation =
                recorder.begin("deepseek", "deepseek-flash", ModelCallRecorder.MODE_STREAM);
        List<ChatResponse> chunks = observation
                .instrument(Flux.just(response("a", null, null)))
                .collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(appender.list).isEmpty();
    }

    // ============================ 辅助方法 ============================

    private ChatResponse response(String text, DefaultUsage usage, String id) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder();
        if (usage != null) {
            builder.usage(usage);
        }
        if (id != null) {
            builder.id(id);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), builder.build());
    }

    private Map<String, Object> singleRecord() {
        assertThat(appender.list).hasSize(1);
        return parse(0);
    }

    private Map<String, Object> parse(int index) {
        try {
            return json.readValue(appender.list.get(index).getFormattedMessage(), Map.class);
        } catch (Exception e) {
            throw new AssertionError("终态记录不是合法 JSON", e);
        }
    }
}
