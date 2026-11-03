package com.zl.d21.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 模型调用韧性支持：全链路<b>唯一</b>的重试与超时责任层。
 *
 * <p>同步方法 {@link #executeBlocking(Supplier)} 用显式有限循环实现，流式方法
 * {@link #guardFlux(Flux)} 用 Reactor 算子实现；两者共享同一套
 * {@link ModelExceptionClassifier} 分类与 {@link ModelResilienceProperties} 配置，
 * 保证同步/流式口径一致，且不与 Spring AI 内置 RetryTemplate（已置为不重试）叠加。</p>
 *
 * <p>核心保证：仅对确认可重试的分类重试；总尝试次数受限；退避带抖动且受总时限约束；
 * 流式仅在首个内容片段之前允许重试，首片段后失败只透传错误，不重放、不拼接。</p>
 *
 * <p>记忆简语：一层重试两（次）封顶，抖动退避守时限，流前可重试、流后不重放。</p>
 */
@Slf4j
@Component
public class ModelResilienceSupport {

    private final ModelExceptionClassifier classifier;

    private final ModelResilienceProperties properties;

    public ModelResilienceSupport(ModelExceptionClassifier classifier,
                                  ModelResilienceProperties properties) {
        this.classifier = classifier;
        this.properties = properties;
    }

    /**
     * 同步阻塞执行：对可重试失败做有限重试。
     *
     * @param operation 实际模型调用
     * @param <T>       返回类型
     * @return 成功结果
     * @throws ModelInvocationException 不可重试、重试耗尽或受总时限约束时抛出
     */
    public <T> T executeBlocking(Supplier<T> operation) {
        long deadlineNanos = System.nanoTime() + properties.getTotalDeadline().toNanos();
        int maxAttempts = properties.getMaxAttempts();
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (Throwable error) {
                lastError = error;
                ModelErrorCategory category = classifier.classify(error);
                if (!isRetryable(category)) {
                    throw new ModelInvocationException(category,
                            "模型调用不可重试，分类=" + category, error);
                }
                if (attempt >= maxAttempts) {
                    throw new ModelInvocationException(category,
                            "模型调用重试耗尽，分类=" + category, error);
                }
                long waitNanos = computeBackoffNanos(attempt);
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L || waitNanos > remainingNanos) {
                    throw new ModelInvocationException(category,
                            "模型调用受总时限约束停止重试，分类=" + category, error);
                }
                log.warn("模型同步调用将进行第{}次重试，分类={}", attempt, category);
                sleep(waitNanos);
            }
        }
        throw new ModelInvocationException(ModelErrorCategory.UNKNOWN,
                "模型调用未取得结果", lastError);
    }

    /**
     * 流式守卫：空闲/总时限超时 + 首片段前有限重试。
     *
     * @param source 原始模型流
     * @param <T>    分片类型
     * @return 受治理的流
     */
    public <T> Flux<T> guardFlux(Flux<T> source) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        int retryCount = Math.max(0, properties.getMaxAttempts() - 1);

        Retry retrySpec = Retry.backoff(retryCount, properties.getBackoff().getInitial())
                .maxBackoff(properties.getBackoff().getMaxInterval())
                .jitter(properties.getBackoff().getJitterFactor())
                .filter(error -> !emitted.get() && isRetryable(classifier.classify(error)));

        return Flux.defer(() -> source
                .doOnNext(next -> emitted.set(true))
                // 首个片段前受总时限约束，首个片段之后按空闲超时约束
                .timeout(Mono.delay(properties.getTotalDeadline()),
                        signal -> Mono.delay(properties.getStreamIdleTimeout()))
                .retryWhen(retrySpec)
                .onErrorMap(this::toInvocationException));
    }

    /**
     * 按分类判断是否可重试，读取超时受开关控制。
     */
    private boolean isRetryable(ModelErrorCategory category) {
        if (category == ModelErrorCategory.READ_TIMEOUT) {
            return properties.isRetryOnReadTimeout();
        }
        return category.isRetryable();
    }

    /**
     * 计算带抖动的退避时长（纳秒）。
     */
    private long computeBackoffNanos(int attempt) {
        ModelResilienceProperties.Backoff backoff = properties.getBackoff();
        long base = (long) (backoff.getInitial().toNanos()
                * Math.pow(backoff.getMultiplier(), attempt - 1));
        base = Math.min(base, backoff.getMaxInterval().toNanos());
        double jitter = backoff.getJitterFactor();
        if (jitter > 0.0d) {
            double factor = 1.0d - jitter
                    + ThreadLocalRandom.current().nextDouble(2.0d * jitter);
            base = (long) (base * factor);
        }
        return Math.max(0L, base);
    }

    private void sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new ModelInvocationException(ModelErrorCategory.CLIENT_CANCELLED,
                    "模型调用退避等待被中断", interruptedException);
        }
    }

    /**
     * 把 Reactor 终端错误统一转换为 {@link ModelInvocationException}。
     */
    private Throwable toInvocationException(Throwable error) {
        if (error instanceof ModelInvocationException invocationException) {
            return invocationException;
        }
        Throwable unwrapped = reactor.core.Exceptions.unwrap(error);
        if (unwrapped instanceof ModelInvocationException invocationException) {
            return invocationException;
        }
        ModelErrorCategory category = classifier.classify(unwrapped);
        return new ModelInvocationException(category,
                "模型流式调用失败，分类=" + category, unwrapped);
    }
}
