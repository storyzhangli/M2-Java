package com.zl.d23.observability;

import com.zl.d23.adapter.NativeUsageReader;
import com.zl.d23.logging.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次模型调用的请求级观测上下文（Model Call Observation）。
 *
 * <p>对象在 Servlet 线程上创建，因此可以可靠地从 MDC 取到 requestId 并记录起始单调时钟；
 * 随后这些值作为对象内部状态携带，即使流式算子在 Reactor 线程上执行，也不依赖未经验证的
 * ThreadLocal 传播，从根本上避免串号与上下文泄漏。</p>
 *
 * <p>终态保证：用 {@link AtomicBoolean} 保证一次调用只写一条终态记录，
 * SUCCESS、ERROR、CANCELLED 三条路径互斥；取消、异常、超时都会终结。</p>
 */
public class ModelCallObservation {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final ModelCallRecorder recorder;

    private final ModelObservabilityProperties properties;

    /** 原生用量读取器，透传给 {@link TokenUsageSnapshot}；空列表时走可移植回退。 */
    private final List<NativeUsageReader> usageReaders;

    private final String provider;

    private final String model;

    private final String callMode;

    /** 在 Servlet 线程上捕获的关联 ID；后续不再依赖 MDC。 */
    private final String capturedRequestId;

    /** 起始单调时钟（纳秒），用于计算耗时。 */
    private final long startNanos = System.nanoTime();

    /** 起始墙钟时间，仅用于人类可读的时间戳，不参与耗时计算。 */
    private final String startTime = TIME_FORMATTER.format(Instant.now());

    /** 终态守卫：保证只输出一条记录。 */
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    public ModelCallObservation(ModelCallRecorder recorder,
                                ModelObservabilityProperties properties,
                                List<NativeUsageReader> usageReaders,
                                String provider,
                                String model,
                                String callMode) {
        this.recorder = recorder;
        this.properties = properties;
        this.usageReaders = usageReaders;
        this.provider = provider;
        this.model = model;
        this.callMode = callMode;
        this.capturedRequestId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
    }

    // ============================ 同步路径 ============================

    /**
     * 同步成功：从 ChatResponse 提取 usage 与 response id，写 SUCCESS 终态，并原样返回响应。
     */
    public ChatResponse recordSuccess(ChatResponse response) {
        if (!recorder.isEnabled() || response == null) {
            return response;
        }
        TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(extractUsage(response), usageReaders);
        String responseId = extractResponseId(response);
        emit(CallOutcome.SUCCESS, null, snapshot, responseId, null);
        return response;
    }

    /**
     * 同步/流式异常：写 ERROR 终态。异常时没有 usage，按 NOT_RETURNED 处理；错误码只取类名，不回显消息。
     */
    public void recordError(Throwable error) {
        if (!recorder.isEnabled()) {
            return;
        }
        String errorCode = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        emit(CallOutcome.ERROR, errorCode, TokenUsageSnapshot.notReturned(), null, null);
    }

    // ============================ 流式路径 ============================

    /**
     * 包装模型原生 ChatResponse 流，在不改变分片内容与对外 SSE 契约的前提下观测终态。
     *
     * <p>usage 处理：只保留“最后一个携带有效 usage 的分片”，绝不把各分片 usage 相加；
     * 终态时若没有任何分片携带 usage，则记 NOT_RETURNED。</p>
     */
    public Flux<ChatResponse> instrument(Flux<ChatResponse> source) {
        if (!recorder.isEnabled()) {
            return source;
        }

        AtomicLong firstChunkNanos = new AtomicLong(0L);
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> responseId = new AtomicReference<>();

        return source
                .doOnNext(chunk -> {
                    if (firstChunkNanos.compareAndSet(0L, System.nanoTime())) {
                        // 首个分片到达，记录首 Chunk 时刻
                    }
                    if (chunk == null) {
                        return;
                    }
                    Usage usage = extractUsage(chunk);
                    if (hasAnyToken(usage)) {
                        // 仅覆盖，不累加：最终 usage 以最后一个有效分片为唯一来源
                        lastUsage.set(usage);
                    }
                    String id = extractResponseId(chunk);
                    if (id != null) {
                        responseId.set(id);
                    }
                })
                .doOnComplete(() -> {
                    TokenUsageSnapshot snapshot = TokenUsageSnapshot.from(lastUsage.get(), usageReaders);
                    emit(CallOutcome.SUCCESS, null, snapshot, responseId.get(),
                            timeToFirstChunkMs(firstChunkNanos.get()));
                })
                .doOnError(error -> {
                    String errorCode = error.getClass().getSimpleName();
                    emit(CallOutcome.ERROR, errorCode,
                            TokenUsageSnapshot.from(lastUsage.get(), usageReaders), responseId.get(),
                            timeToFirstChunkMs(firstChunkNanos.get()));
                })
                .doOnCancel(() -> emit(CallOutcome.CANCELLED, null,
                        TokenUsageSnapshot.from(lastUsage.get(), usageReaders), responseId.get(),
                        timeToFirstChunkMs(firstChunkNanos.get())));
    }

    // ============================ 内部方法 ============================

    /** 统一构造并输出终态记录，terminated 守卫保证只写一次。 */
    private void emit(CallOutcome outcome,
                      String errorCode,
                      TokenUsageSnapshot snapshot,
                      String responseId,
                      Long timeToFirstChunkMs) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String endTime = TIME_FORMATTER.format(Instant.now());
        ModelCallRecord record = new ModelCallRecord(
                capturedRequestId,
                capturedRequestId,
                provider,
                model,
                callMode,
                startTime,
                endTime,
                durationMs,
                outcome,
                errorCode,
                snapshot.status(),
                snapshot.source(),
                snapshot.promptTokens(),
                snapshot.completionTokens(),
                snapshot.totalTokens(),
                responseId,
                timeToFirstChunkMs);
        recorder.emit(record);
    }

    /** 计算首 Chunk 延迟（毫秒）；未启用或未发射过分片时返回 null。 */
    private Long timeToFirstChunkMs(long firstChunkNanos) {
        if (!properties.isRecordTimeToFirstChunk() || firstChunkNanos == 0L) {
            return null;
        }
        return (firstChunkNanos - startNanos) / 1_000_000L;
    }

    /** 安全读取 ChatResponse 中的 usage，metadata 缺失时返回 null。 */
    private static Usage extractUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }

    /** 安全读取 Provider response id。 */
    private static String extractResponseId(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        return metadata == null ? null : metadata.getId();
    }

    /** 判断 usage 是否携带至少一个非空 token 字段；EmptyUsage 与 null 均视为无数据。 */
    private static boolean hasAnyToken(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return false;
        }
        return usage.getPromptTokens() != null
                || usage.getCompletionTokens() != null
                || usage.getTotalTokens() != null;
    }
}
