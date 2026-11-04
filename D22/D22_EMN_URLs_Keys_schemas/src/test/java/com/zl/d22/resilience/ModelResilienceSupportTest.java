package com.zl.d22.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型韧性支持测试（T03~T10、T12）。
 *
 * <p>用确定性的受控失败（计数器 + 立即抛出/可控延迟）验证有限重试的次数、退避、
 * 总时限约束，以及流式“首片段前可重试、首片段后不重放”的语义。
 * 不启动 Spring 上下文、不触外网。</p>
 */
class ModelResilienceSupportTest {

    /** 构造使用快速退避的支持类，便于测试。 */
    private ModelResilienceSupport support(Consumer<ModelResilienceProperties> tweak) {
        ModelResilienceProperties properties = new ModelResilienceProperties();
        properties.getBackoff().setInitial(Duration.ofMillis(20));
        properties.getBackoff().setJitterFactor(0.0d);
        properties.getBackoff().setMaxInterval(Duration.ofSeconds(2));
        properties.setStreamIdleTimeout(Duration.ofMillis(60));
        properties.setTotalDeadline(Duration.ofSeconds(10));
        tweak.accept(properties);
        properties.validate();
        return new ModelResilienceSupport(new ModelExceptionClassifier(), properties);
    }

    @Test
    @DisplayName("T03 同步可重试失败后成功：恰好执行 2 次并返回结果")
    void blockingRetryThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ModelResilienceSupport support = support(p -> { });
        String result = support.executeBlocking(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "io", new java.net.ConnectException("refused"));
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("T04 同步持续不可用：达到上限后抛出统一异常且分类正确")
    void blockingPersistentFailure() {
        AtomicInteger calls = new AtomicInteger();
        ModelResilienceSupport support = support(p -> p.setMaxAttempts(2));
        assertThatThrownBy(() -> support.executeBlocking(() -> {
            calls.incrementAndGet();
            throw new org.springframework.ai.retry.TransientAiException("HTTP 503 - busy");
        })).isInstanceOf(ModelInvocationException.class)
                .extracting(ex -> ((ModelInvocationException) ex).getCategory())
                .isEqualTo(ModelErrorCategory.UPSTREAM_UNAVAILABLE);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("T05 不可重试异常只执行 1 次")
    void nonTransientNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        ModelResilienceSupport support = support(p -> { });
        assertThatThrownBy(() -> support.executeBlocking(() -> {
            calls.incrementAndGet();
            throw new org.springframework.ai.retry.NonTransientAiException("HTTP 401 - unauthorized");
        })).isInstanceOf(ModelInvocationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("T06 总时限约束：剩余时间不足以退避时放弃重试")
    void deadlineStopsRetry() {
        AtomicInteger calls = new AtomicInteger();
        ModelResilienceSupport support = support(p -> {
            p.setConnectTimeout(Duration.ofMillis(10));
            p.setTotalDeadline(Duration.ofMillis(30));
            p.getBackoff().setInitial(Duration.ofMillis(500));
        });
        assertThatThrownBy(() -> support.executeBlocking(() -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.ResourceAccessException(
                    "io", new java.net.ConnectException("refused"));
        })).isInstanceOf(ModelInvocationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("T07 无抖动退避确定性等待至少一个退避周期")
    void backoffActuallyWaited() {
        AtomicInteger calls = new AtomicInteger();
        ModelResilienceSupport support = support(p ->
                p.getBackoff().setInitial(Duration.ofMillis(120)));
        long start = System.nanoTime();
        support.executeBlocking(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "io", new java.net.ConnectException("refused"));
            }
            return "ok";
        });
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("T12 读取超时默认不重试，开启开关后可重试")
    void readTimeoutSwitch() {
        AtomicInteger defaultCalls = new AtomicInteger();
        ModelResilienceSupport defaultSupport = support(p -> { });
        assertThatThrownBy(() -> defaultSupport.executeBlocking(() -> {
            defaultCalls.incrementAndGet();
            throw new org.springframework.web.client.ResourceAccessException(
                    "io", new java.net.http.HttpTimeoutException("read timed out"));
        })).isInstanceOf(ModelInvocationException.class);
        assertThat(defaultCalls).hasValue(1);

        AtomicInteger enabledCalls = new AtomicInteger();
        ModelResilienceSupport enabledSupport = support(p -> p.setRetryOnReadTimeout(true));
        String result = enabledSupport.executeBlocking(() -> {
            if (enabledCalls.incrementAndGet() == 1) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "io", new java.net.http.HttpTimeoutException("read timed out"));
            }
            return "recovered";
        });
        assertThat(result).isEqualTo("recovered");
        assertThat(enabledCalls).hasValue(2);
    }

    @Test
    @DisplayName("T08 流式首片段前错误可重试，重试后成功且无重复片段")
    void fluxRetryBeforeFirstItem() {
        AtomicInteger subscriptions = new AtomicInteger();
        ModelResilienceSupport support = support(p -> { });
        Flux<String> source = Flux.defer(() -> {
            if (subscriptions.incrementAndGet() == 1) {
                return Flux.error(new org.springframework.web.client.ResourceAccessException(
                        "io", new java.net.ConnectException("refused")));
            }
            return Flux.just("a", "b");
        });
        List<String> items = support.guardFlux(source).collectList().block();
        assertThat(items).containsExactly("a", "b");
        assertThat(subscriptions).hasValue(2);
    }

    @Test
    @DisplayName("T09 流式首片段后即使命中可重试错误也不重试，错误透传")
    void fluxNoRetryAfterFirstItem() {
        AtomicInteger subscriptions = new AtomicInteger();
        ModelResilienceSupport support = support(p -> { });
        Flux<String> source = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.concat(Flux.just("a"), Flux.error(
                    new org.springframework.web.client.ResourceAccessException(
                            "io", new java.net.ConnectException("refused"))));
        });
        assertThatThrownBy(() -> support.guardFlux(source).blockLast())
                .isInstanceOf(ModelInvocationException.class)
                .extracting(ex -> ((ModelInvocationException) ex).getCategory())
                .isEqualTo(ModelErrorCategory.CONNECT_FAILURE);
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    @DisplayName("T10 流式空闲超时抛出统一异常并归为读取超时")
    void fluxIdleTimeout() {
        ModelResilienceSupport support = support(p ->
                p.setStreamIdleTimeout(Duration.ofMillis(40)));
        Flux<String> source = Flux.concat(Flux.just("a"), Mono.never());
        assertThatThrownBy(() -> support.guardFlux(source).blockLast())
                .isInstanceOf(ModelInvocationException.class)
                .extracting(ex -> ((ModelInvocationException) ex).getCategory())
                .isEqualTo(ModelErrorCategory.READ_TIMEOUT);
    }
}
