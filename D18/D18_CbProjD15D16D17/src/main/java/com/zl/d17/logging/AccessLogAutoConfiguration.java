package com.zl.d17.logging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 访问日志自动装配。
 *
 * <p>{@code app.access-log.enabled=false}（缺省视为 true）时本配置整体不生效，
 * 两个过滤器与切面都不注册，做到可一键关闭。过滤器顺序固定：</p>
 * <ol>
 *     <li>{@link TraceIdFilter}：最高优先级，先于一切组件建立 requestId/MDC；</li>
 *     <li>{@link CachedBodyAccessLogFilter}：次之，包装请求/响应并记录 HTTP 事件。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
@ConditionalOnProperty(prefix = "app.access-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccessLogAutoConfiguration {

    /** 统一收口工具 Bean。 */
    @Bean
    public LogMaskingUtils logMaskingUtils(AccessLogProperties properties) {
        return new LogMaskingUtils(properties);
    }

    /** 请求标识过滤器：最先执行。 */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(
            AccessLogProperties properties, LogMaskingUtils logMaskingUtils) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdFilter(properties, logMaskingUtils));
        registration.addUrlPatterns("/*");
        registration.setName("d13TraceIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 访问日志过滤器：在 TraceId 之后执行，确保可直接读取 MDC 中的 requestId。 */
    @Bean
    public FilterRegistrationBean<CachedBodyAccessLogFilter> cachedBodyAccessLogFilterRegistration(
            AccessLogProperties properties, LogMaskingUtils logMaskingUtils) {
        FilterRegistrationBean<CachedBodyAccessLogFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CachedBodyAccessLogFilter(properties, logMaskingUtils));
        registration.addUrlPatterns("/*");
        registration.setName("d13CachedBodyAccessLogFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
