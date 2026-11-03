package com.zl.d21.resilience;

import lombok.Getter;

/**
 * 模型调用在有限重试耗尽（或命中不可重试分类）后抛出的统一异常。
 *
 * <p>它携带确定的 {@link ModelErrorCategory}，由全局异常处理器据此映射对外业务码，
 * 从而把“分类、重试、对外映射”三段解耦。异常消息仅使用安全的分类描述，
 * 不包含上游原始报文、请求头或密钥。</p>
 */
@Getter
public class ModelInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 最终归一后的内部分类。 */
    private final ModelErrorCategory category;

    public ModelInvocationException(ModelErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }
}
