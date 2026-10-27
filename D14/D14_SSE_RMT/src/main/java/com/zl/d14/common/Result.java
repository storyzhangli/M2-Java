package com.zl.d14.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应返回体。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务返回码，见 {@link ResultCode}。 */
    private Integer code;

    /** 提示信息（对外可读，不含堆栈、密钥等敏感内容）。 */
    private String msg;

    /** 业务数据。 */
    private T data;

    public Result() {
    }

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 成功返回（带数据）。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 失败返回（指定返回码枚举）。
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 失败返回（指定返回码与自定义提示）。
     */
    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }
}
