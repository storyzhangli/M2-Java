package com.zl.d22.resilience;

import com.zl.d22.common.AiConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型异常分类器：把同步/流式链路上任意 {@link Throwable} 归一到 {@link ModelErrorCategory}。
 *
 * <p>分类策略（按可靠性从高到低）：</p>
 * <ol>
 *     <li>沿异常 cause 链识别确定的 JDK/网络/取消信号（建连超时、读取超时、连接拒绝、
 *     未知主机、SSL 握手、取消），这是区分“建连失败可重试”与“读取超时默认不重试”的关键；</li>
 *     <li>对 Spring AI 的 {@link NonTransientAiException}/{@link TransientAiException}，
 *     从其消息中解析上游 HTTP 状态码再细分；</li>
 *     <li>顶层已知异常与合理默认值兜底，仍无法归类返回 {@link ModelErrorCategory#UNKNOWN}。</li>
 * </ol>
 *
 * <p>说明：Spring AI 错误处理器（字节码实证）将状态码格式化为 {@code "HTTP nnn - ..."}，
 * 本类用兼容前缀的正则解析；解析失败时走类型兜底，不影响对外安全契约。</p>
 */
@Slf4j
@Component
public class ModelExceptionClassifier {

    /** 兼容 "HTTP 404 - ..." 与 "404 - ..." 两种格式的状态码提取正则。 */
    private static final Pattern STATUS_PATTERN = Pattern.compile("(?:HTTP\\s+)?(\\d{3})\\s*-\\s");

    /**
     * 归一分类。
     *
     * @param throwable 模型调用抛出的原始异常，可为 {@code null}
     * @return 确定的内部分类，永不返回 {@code null}
     */
    public ModelErrorCategory classify(Throwable throwable) {
        if (throwable == null) {
            return ModelErrorCategory.UNKNOWN;
        }
        if (throwable instanceof ModelInvocationException invocationException) {
            return invocationException.getCategory();
        }

        // 1. cause 链上的确定网络/取消信号
        for (Throwable node = throwable; node != null; node = node.getCause()) {
            ModelErrorCategory typed = matchByType(node);
            if (typed != null) {
                return typed;
            }
        }

        // 2. Spring AI 分类异常：按消息中的状态码细分
        ModelErrorCategory byStatus = matchByStatusCode(throwable);
        if (byStatus != null) {
            return byStatus;
        }

        // 3. 顶层已知异常兜底
        if (throwable instanceof AiConfigurationException) {
            return ModelErrorCategory.AUTH_ERROR;
        }
        if (throwable instanceof IllegalArgumentException) {
            return ModelErrorCategory.PARAM_ERROR;
        }
        if (throwable instanceof NonTransientAiException) {
            return ModelErrorCategory.AUTH_ERROR;
        }
        if (throwable instanceof TransientAiException) {
            return ModelErrorCategory.UPSTREAM_UNAVAILABLE;
        }
        return ModelErrorCategory.UNKNOWN;
    }

    /**
     * 按异常具体类型匹配（用于 cause 链上的确定信号）。
     */
    private ModelErrorCategory matchByType(Throwable node) {
        if (node instanceof InterruptedException || node instanceof CancellationException) {
            return ModelErrorCategory.CLIENT_CANCELLED;
        }
        // 注意：HttpConnectTimeoutException 是 HttpTimeoutException 的子类，必须先判断
        if (node instanceof HttpConnectTimeoutException) {
            return ModelErrorCategory.CONNECT_FAILURE;
        }
        if (node instanceof HttpTimeoutException) {
            return ModelErrorCategory.READ_TIMEOUT;
        }
        // Reactor 空闲/总时限超时（与 JDK HttpTimeoutException 不同继承体系）
        if (node instanceof TimeoutException) {
            return ModelErrorCategory.READ_TIMEOUT;
        }
        if (node instanceof ConnectException || node instanceof UnknownHostException) {
            return ModelErrorCategory.CONNECT_FAILURE;
        }
        if (node instanceof SSLException) {
            // TLS 握手发生在请求发出之前，可安全重试
            return ModelErrorCategory.CONNECT_FAILURE;
        }
        if (node instanceof SocketTimeoutException socketTimeout) {
            String message = socketTimeout.getMessage();
            if (message != null && message.toLowerCase().contains("connect")) {
                return ModelErrorCategory.CONNECT_FAILURE;
            }
            return ModelErrorCategory.READ_TIMEOUT;
        }
        return null;
    }

    /**
     * 从 Spring AI 分类异常的消息中解析状态码并映射。
     */
    private ModelErrorCategory matchByStatusCode(Throwable throwable) {
        for (Throwable node = throwable; node != null; node = node.getCause()) {
            if (node instanceof NonTransientAiException
                    || node instanceof TransientAiException) {
                Integer status = extractStatus(node.getMessage());
                if (status != null) {
                    return mapStatus(status);
                }
            }
        }
        return null;
    }

    private Integer extractStatus(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = STATUS_PATTERN.matcher(message);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * 上游 HTTP 状态码到内部分类的映射。
     */
    private ModelErrorCategory mapStatus(int status) {
        if (status == 429) {
            return ModelErrorCategory.RATE_LIMIT_RECOVERABLE;
        }
        if (status == 408) {
            return ModelErrorCategory.READ_TIMEOUT;
        }
        if (status == 401 || status == 403) {
            return ModelErrorCategory.AUTH_ERROR;
        }
        if (status == 404) {
            return ModelErrorCategory.MODEL_NOT_FOUND;
        }
        if (status == 409 || status == 422) {
            return ModelErrorCategory.CONTENT_POLICY_REJECT;
        }
        if (status >= 500) {
            return ModelErrorCategory.UPSTREAM_UNAVAILABLE;
        }
        if (status == 400) {
            return ModelErrorCategory.PARAM_ERROR;
        }
        if (status >= 400) {
            return ModelErrorCategory.AUTH_ERROR;
        }
        return ModelErrorCategory.UNKNOWN;
    }
}
