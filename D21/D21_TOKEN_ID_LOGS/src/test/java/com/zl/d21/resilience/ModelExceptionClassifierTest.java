package com.zl.d21.resilience;

import com.zl.d21.common.AiConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型异常分类器测试（T01/T02）。
 *
 * <p>纯单元测试，不启动 Spring 上下文、不触外网：分别验证 HTTP 状态码细分与
 * cause 链上建连/读取超时的区分口径。</p>
 */
class ModelExceptionClassifierTest {

    private final ModelExceptionClassifier classifier = new ModelExceptionClassifier();

    @ParameterizedTest
    @CsvSource({
            "400, PARAM_ERROR",
            "401, AUTH_ERROR",
            "403, AUTH_ERROR",
            "404, MODEL_NOT_FOUND",
            "409, CONTENT_POLICY_REJECT",
            "422, CONTENT_POLICY_REJECT",
            "429, RATE_LIMIT_RECOVERABLE",
            "500, UPSTREAM_UNAVAILABLE",
            "502, UPSTREAM_UNAVAILABLE",
            "503, UPSTREAM_UNAVAILABLE",
            "504, UPSTREAM_UNAVAILABLE"
    })
    @DisplayName("T01 按 Spring AI 异常消息中的状态码细分")
    void classifyByHttpStatus(int status, String expected) {
        NonTransientAiException nonTransient =
                new NonTransientAiException("HTTP " + status + " - upstream body");
        assertThat(classifier.classify(nonTransient).name()).isEqualTo(expected);

        // 兼容无 HTTP 前缀的格式
        TransientAiException transientError =
                new TransientAiException(status + " - upstream body");
        assertThat(classifier.classify(transientError).name()).isEqualTo(expected);
    }

    @Test
    @DisplayName("T02 建连超时（HttpConnectTimeoutException）归为可重试的建连失败")
    void connectTimeout_isConnectFailure() {
        ResourceAccessException error = new ResourceAccessException("I/O error",
                new HttpConnectTimeoutException("connect timed out"));
        ModelErrorCategory category = classifier.classify(error);
        assertThat(category).isEqualTo(ModelErrorCategory.CONNECT_FAILURE);
        assertThat(category.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("T02 读取超时（HttpTimeoutException）归为默认不重试的读取超时")
    void readTimeout_isReadTimeout() {
        ResourceAccessException error = new ResourceAccessException("I/O error",
                new HttpTimeoutException("request timed out"));
        ModelErrorCategory category = classifier.classify(error);
        assertThat(category).isEqualTo(ModelErrorCategory.READ_TIMEOUT);
        assertThat(category.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("T02 连接拒绝/未知主机归为建连失败")
    void connectRefusedAndUnknownHost_areConnectFailure() {
        assertThat(classifier.classify(
                new ResourceAccessException("I/O", new ConnectException("refused"))))
                .isEqualTo(ModelErrorCategory.CONNECT_FAILURE);
        assertThat(classifier.classify(
                new ResourceAccessException("I/O", new UnknownHostException("no host"))))
                .isEqualTo(ModelErrorCategory.CONNECT_FAILURE);
    }

    @Test
    @DisplayName("T02 SocketTimeoutException 按消息区分建连/读取")
    void socketTimeout_classifiedByMessage() {
        assertThat(classifier.classify(new SocketTimeoutException("connect timed out")))
                .isEqualTo(ModelErrorCategory.CONNECT_FAILURE);
        assertThat(classifier.classify(new SocketTimeoutException("Read timed out")))
                .isEqualTo(ModelErrorCategory.READ_TIMEOUT);
    }

    @Test
    @DisplayName("T01 配置异常/取消异常/未知异常分类正确")
    void knownAndUnknownFallbacks() {
        assertThat(classifier.classify(new AiConfigurationException("bad key")))
                .isEqualTo(ModelErrorCategory.AUTH_ERROR);
        assertThat(classifier.classify(new CancellationException("cancelled")))
                .isEqualTo(ModelErrorCategory.CLIENT_CANCELLED);
        assertThat(classifier.classify(new RuntimeException("mystery")))
                .isEqualTo(ModelErrorCategory.UNKNOWN);
        assertThat(classifier.classify(null))
                .isEqualTo(ModelErrorCategory.UNKNOWN);
    }
}
