package com.zl.d15.common;

import lombok.Getter;

/**
 * 统一业务返回码。
 *
 * <p>HTTP 层统一返回 200，调用结果通过该业务码区分，保持 {@code code/msg/data} 契约稳定。</p>
 */
@Getter
public enum ResultCode {

    /** 成功。 */
    SUCCESS(200, "success"),

    /** 请求参数校验失败（空白、超长、报文不可读等）。 */
    PARAM_INVALID(4001, "请求参数校验失败"),

    /** AI 配置/鉴权/客户端错误：缺失或占位密钥、上游 4xx（含 401）。 */
    AI_CONFIG_ERROR(5001, "AI 服务配置或鉴权异常，请检查密钥与参数配置"),

    /** AI 服务端暂不可用：上游 5xx/超时/限流且重试耗尽。 */
    AI_UNAVAILABLE(5002, "AI 服务暂不可用，请稍后重试"),

    /** 未预期的系统兜底错误。 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试");

    private final Integer code;

    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
