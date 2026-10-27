package com.zl.d01.springai.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求标识（requestId / traceId）过滤器。
 *
 * <p>职责边界：只负责“生成或接收请求标识 → 写入 MDC → 回写响应头 → 请求结束清理”，
 * 不做任何正文缓存与打印。优先级最高，保证后续过滤器、AOP 切面、业务日志都能从 MDC 取到同一 ID。</p>
 *
 * <ul>
 *     <li>优先复用上游请求头中的 ID（名称由 {@code app.access-log.request-id-header} 配置）；</li>
 *     <li>缺失或含非法字符时生成 32 位无横线 UUID；</li>
 *     <li>在 finally 中清理 MDC，避免 Tomcat 工作线程复用导致“串号”。</li>
 * </ul>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 中保存 requestId 的键名，需与 logback pattern 的 %X{requestId} 一致。 */
    public static final String MDC_REQUEST_ID = "requestId";

    /** 作为请求属性暴露给后续组件的属性名。 */
    public static final String REQUEST_ATTRIBUTE = "com.zl.d01.springai.logging.REQUEST_ID";

    private final AccessLogProperties properties;
    private final LogMaskingUtils maskingUtils;

    public TraceIdFilter(AccessLogProperties properties, LogMaskingUtils maskingUtils) {
        this.properties = properties;
        this.maskingUtils = maskingUtils;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = maskingUtils.sanitizeInboundRequestId(
                request.getHeader(properties.getRequestIdHeader()));
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            request.setAttribute(REQUEST_ATTRIBUTE, requestId);
            // 回写响应头，调用方/网关可直接拿到并与服务端日志关联
            response.setHeader(properties.getRequestIdHeader(), requestId);
            filterChain.doFilter(request, response);
        } finally {
            // 工作线程会被复用，必须清理，避免下一个请求沿用上个 requestId
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
