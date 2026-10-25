package com.zl.d01.springai.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>将各类异常归一为统一 {@link Result} 结构（HTTP 状态保持 200，业务结果通过 code 区分）。
 * 对外响应<b>只包含固定/安全提示，绝不回显异常堆栈、上游原始报文、请求头或 API Key</b>；
 * 详细诊断信息仅写入服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Bean Validation 注解校验失败（@NotBlank / @Size 等）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_INVALID.getMsg());
        log.warn("参数校验失败: {}", detail);
        return Result.fail(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /**
     * 约束违反、请求体不可读/缺失、以及业务层主动抛出的非法参数（如可配置长度上限）。
     */
    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public Result<Void> handleBadRequest(Exception ex) {
        String detail;
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null) {
            detail = ex.getMessage();
        } else if (ex instanceof ConstraintViolationException && ex.getMessage() != null) {
            detail = ex.getMessage();
        } else {
            detail = "请求体缺失或格式非法，需要 JSON 报文且包含 message 字段";
        }
        log.warn("请求参数异常: {}", detail);
        return Result.fail(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /**
     * 真实模式下密钥缺失/占位等配置问题，调用前拦截，未产生外部请求。
     */
    @ExceptionHandler(AiConfigurationException.class)
    public Result<Void> handleAiConfiguration(AiConfigurationException ex) {
        // 该消息为系统自定义安全文案，不含密钥，可入日志；对外仍返回稳定的统一提示
        log.error("AI 配置校验失败: {}", ex.getMessage());
        return Result.fail(ResultCode.AI_CONFIG_ERROR);
    }

    /**
     * Spring AI 不可重试异常：上游 4xx（含 401 鉴权失败、参数错误）。
     * 仅记录异常类型，不记录可能含上游报文的 message，更不记录请求头。
     */
    @ExceptionHandler(NonTransientAiException.class)
    public Result<Void> handleNonTransient(NonTransientAiException ex) {
        log.error("AI 客户端/鉴权异常，异常类型: {}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.AI_CONFIG_ERROR);
    }

    /**
     * Spring AI 可重试异常：上游 5xx/超时/限流且重试耗尽。
     */
    @ExceptionHandler(TransientAiException.class)
    public Result<Void> handleTransient(TransientAiException ex) {
        log.error("AI 服务端临时异常且重试耗尽，异常类型: {}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.AI_UNAVAILABLE);
    }

    /**
     * 兜底异常：完整堆栈仅写入服务端日志，对外返回通用提示。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception ex) {
        log.error("未预期的系统异常", ex);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
