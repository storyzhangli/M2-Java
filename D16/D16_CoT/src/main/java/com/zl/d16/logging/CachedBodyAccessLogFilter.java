package com.zl.d16.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Servlet 缓存包装器的 HTTP 全生命周期访问日志过滤器。
 *
 * <p>与 {@link ControllerAccessLogAspect} 的分工：</p>
 * <ul>
 *     <li>AOP 切面只能看到“成功进入 Controller 的方法”入参与返回对象，无法保证拿到最终 HTTP 状态码、
 *     异常处理器写出的响应体，也覆盖不到未进入 Controller 的请求（如 404）；</li>
 *     <li>本过滤器覆盖完整 HTTP 生命周期：用 {@link ContentCachingRequestWrapper} 缓存请求已读取字节、
 *     {@link ContentCachingResponseWrapper} 缓存响应体，在链返回后记录最终状态与正文，并负责把响应
 *     {@code copyBodyToResponse()} 原样写回客户端。</li>
 * </ul>
 *
 * <p>安全边界：multipart/二进制/流式（SSE）正文不缓存不打印，仅记录 Content-Type、大小与跳过原因；
 * 所有日志异常被吞掉并告警，绝不改变业务响应或遮蔽原始异常。</p>
 */
public class CachedBodyAccessLogFilter extends OncePerRequestFilter {

    /** 统一访问日志 Logger，测试与生产均按该名称采集。 */
    public static final String ACCESS_LOGGER_NAME = "ACCESS_LOG";

    private static final Logger accessLogger = LoggerFactory.getLogger(ACCESS_LOGGER_NAME);

    private final AccessLogProperties properties;
    private final LogMaskingUtils maskingUtils;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public CachedBodyAccessLogFilter(AccessLogProperties properties, LogMaskingUtils maskingUtils) {
        this.properties = properties;
        this.maskingUtils = maskingUtils;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return properties.getExcludePaths().stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startNanos = System.nanoTime();
        Throwable thrown = null;
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            try {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                logRequest(requestWrapper);
                logResponse(requestWrapper, responseWrapper, elapsedMs, thrown);
            } catch (Exception loggingFailure) {
                // 日志失败绝不允许影响业务或覆盖原始异常
                accessLogger.warn("访问日志记录失败（不影响业务）: {}", loggingFailure.getMessage());
            } finally {
                // 必须把缓存的响应体重新写回真实响应，否则客户端收到空响应
                responseWrapper.copyBodyToResponse();
            }
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "HTTP_REQUEST");
        event.put("requestId", MDC.get(TraceIdFilter.MDC_REQUEST_ID));
        event.put("method", request.getMethod());
        event.put("uri", request.getRequestURI());
        event.put("query", maskingUtils.sanitizeQuery(request.getQueryString()));
        event.put("clientIp", request.getRemoteAddr());

        if (properties.isIncludeHeaders()) {
            event.put("headers", collectHeaders(request));
        }

        if (properties.isIncludeRequestBody()) {
            event.put("requestBody", describeRequestBody(request));
        }
        accessLogger.info(maskingUtils.toJsonLine(event));
    }

    private void logResponse(ContentCachingRequestWrapper request,
                             ContentCachingResponseWrapper response,
                             long elapsedMs,
                             Throwable thrown) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "HTTP_RESPONSE");
        event.put("requestId", MDC.get(TraceIdFilter.MDC_REQUEST_ID));
        event.put("method", request.getMethod());
        event.put("uri", request.getRequestURI());
        event.put("httpStatus", response.getStatus());
        event.put("elapsedMs", elapsedMs);
        if (thrown != null) {
            event.put("exception", thrown.getClass().getSimpleName());
        }
        if (properties.isIncludeResponseBody()) {
            event.put("responseBody", describeResponseBody(response));
        }
        accessLogger.info(maskingUtils.toJsonLine(event));
    }

    private Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> raw = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> raw.put(name, request.getHeader(name)));
        return maskingUtils.sanitizeHeaders(raw);
    }

    private Map<String, Object> describeRequestBody(ContentCachingRequestWrapper request) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String contentType = request.getContentType();
        byte[] body = request.getContentAsByteArray();
        meta.put("contentType", contentType);
        meta.put("size", body.length);

        if (maskingUtils.isBinaryOrStreaming(contentType)) {
            meta.put("body", null);
            meta.put("skipReason", "binary/multipart/streaming content, body not cached");
            return meta;
        }
        if (body.length == 0) {
            // ContentCachingRequestWrapper 只缓存“被读取过”的字节：GET 无体或请求未进入 Controller（如 404）时为空
            meta.put("body", null);
            meta.put("skipReason", "empty body or input stream never read by framework");
            return meta;
        }
        Charset charset = Charset.isSupported(request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8.name() : request.getCharacterEncoding())
                ? Charset.forName(maskingUtils.resolveCharset(request)) : StandardCharsets.UTF_8;
        String rawText = new String(body, charset);
        LogMaskingUtils.MaskedText masked = maskingUtils.prepareJsonBody(rawText);
        meta.put("body", masked.text());
        meta.put("truncated", masked.truncated());
        return meta;
    }

    private Map<String, Object> describeResponseBody(ContentCachingResponseWrapper response) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String contentType = response.getContentType();
        byte[] body = response.getContentAsByteArray();
        meta.put("contentType", contentType);
        meta.put("size", body.length);

        if (maskingUtils.isBinaryOrStreaming(contentType)) {
            meta.put("body", null);
            meta.put("skipReason", "binary/streaming response, body not logged");
            return meta;
        }
        if (body.length == 0) {
            meta.put("body", null);
            meta.put("skipReason", "empty response body");
            return meta;
        }
        String rawText = new String(body, StandardCharsets.UTF_8);
        LogMaskingUtils.MaskedText masked = maskingUtils.prepareJsonBody(rawText);
        meta.put("body", masked.text());
        meta.put("truncated", masked.truncated());
        return meta;
    }
}
