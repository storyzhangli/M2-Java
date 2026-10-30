package com.zl.d17.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller 访问日志切面：记录“绑定后的方法入参、返回对象、方法级耗时与异常”。
 *
 * <p>为什么在过滤器之外还需要 AOP：</p>
 * <ul>
 *     <li>过滤器看到的是原始字节流与最终 HTTP 报文；切面能直接拿到 Jackson 绑定后的 DTO、
 *         {@code @RequestParam} 等 Java 对象以及 Controller 真实返回对象，语义更清晰；</li>
 *     <li>方法级耗时可隔离视图渲染/序列化开销，便于定位业务性能。</li>
 * </ul>
 *
 * <p>代理限制（须知悉）：Spring AOP 基于动态代理，同类内部 {@code this.method()} 自调用会绕过
 * 代理、不触发 Advice；当前 Controller 的入口方法均由 DispatcherServlet 外部调用，不受影响。</p>
 */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(prefix = "app.access-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ControllerAccessLogAspect {

    private final AccessLogProperties properties;
    private final LogMaskingUtils maskingUtils;

    public ControllerAccessLogAspect(AccessLogProperties properties, LogMaskingUtils maskingUtils) {
        this.properties = properties;
        this.maskingUtils = maskingUtils;
    }

    /**
     * 仅切当前应用 Controller 包内 public 方法，范围显式，不记录框架自身 Bean。
     * 注意：D16 实际 Controller 包为 {@code com.zl.d16.controller}；本切点在历次骨架复制时
     * （D14→D15、D15→D16）都曾遗留旧模块包名导致切面静默不生效，已按当前模块包名修正
     * （后续升级模块时必须同步检查本切点与测试引导的 scanBasePackages）。
     */
    @Around("execution(public * com.zl.d16.controller..*.*(..))")
    public Object aroundController(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String target = signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
        long startNanos = System.nanoTime();

        Map<String, Object> argsView = null;
        try {
            if (properties.isIncludeMethodArgs()) {
                argsView = buildArgsView(signature, pjp.getArgs());
            }
        } catch (Exception argFailure) {
            argsView = Map.of("argsError", argFailure.getClass().getSimpleName());
        }

        try {
            Object result = pjp.proceed();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            safeLogSuccess(target, argsView, result, elapsedMs);
            return result;
        } catch (Throwable ex) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            safeLogError(target, argsView, ex, elapsedMs);
            // 原样抛出，交由 GlobalExceptionHandler 归一处理，绝不吞异常
            throw ex;
        }
    }

    private Map<String, Object> buildArgsView(MethodSignature signature, Object[] args) {
        Map<String, Object> view = new LinkedHashMap<>();
        String[] names = signature.getParameterNames();
        for (int i = 0; i < args.length; i++) {
            String key = (names != null && i < names.length && names[i] != null) ? names[i] : "arg" + i;
            Object value = args[i];
            if (value == null) {
                view.put(key, null);
            } else if (maskingUtils.isContainerArgument(value.getClass())) {
                // ServletRequest/Response 等容器对象不可序列化、无业务记录价值
                view.put(key, "<container:" + value.getClass().getSimpleName() + ">");
            } else {
                LogMaskingUtils.MaskedText masked = maskingUtils.prepareObject(value);
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("value", masked.text());
                field.put("truncated", masked.truncated());
                view.put(key, field);
            }
        }
        return view;
    }

    private void safeLogSuccess(String target, Map<String, Object> args, Object result, long elapsedMs) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "CONTROLLER_ACCESS");
            event.put("requestId", org.slf4j.MDC.get(TraceIdFilter.MDC_REQUEST_ID));
            event.put("controllerMethod", target);
            event.put("elapsedMs", elapsedMs);
            event.put("args", args);
            if (result instanceof org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter) {
                // SseEmitter 等异步响应载体持有容器回调，不能按普通 POJO 序列化，仅记录类型占位
                event.put("result", "<async-emitter:" + result.getClass().getSimpleName() + ">");
                event.put("resultTruncated", false);
            } else {
                LogMaskingUtils.MaskedText maskedResult = maskingUtils.prepareObject(result);
                event.put("result", maskedResult.text());
                event.put("resultTruncated", maskedResult.truncated());
            }
            LoggerFactory.getLogger(CachedBodyAccessLogFilter.ACCESS_LOGGER_NAME)
                    .info(maskingUtils.toJsonLine(event));
        } catch (Exception loggingFailure) {
            log.warn("Controller 成功日志记录失败（不影响业务）: {}", loggingFailure.getMessage());
        }
    }

    private void safeLogError(String target, Map<String, Object> args, Throwable ex, long elapsedMs) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "CONTROLLER_ERROR");
            event.put("requestId", org.slf4j.MDC.get(TraceIdFilter.MDC_REQUEST_ID));
            event.put("controllerMethod", target);
            event.put("elapsedMs", elapsedMs);
            event.put("args", args);
            // 只记录异常类型与消息，完整堆栈仍由 GlobalExceptionHandler 打印，避免重复刷堆栈
            event.put("exception", ex.getClass().getName());
            event.put("errorMessage", ex.getMessage());
            LoggerFactory.getLogger(CachedBodyAccessLogFilter.ACCESS_LOGGER_NAME)
                    .warn(maskingUtils.toJsonLine(event));
        } catch (Exception loggingFailure) {
            log.warn("Controller 异常日志记录失败（不影响业务）: {}", loggingFailure.getMessage());
        }
    }
}
