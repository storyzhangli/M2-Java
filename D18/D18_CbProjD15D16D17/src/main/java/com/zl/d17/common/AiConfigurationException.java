package com.zl.d17.common;

/**
 * AI 配置异常。
 *
 * <p>在真实模式下，当 API Key 缺失、为空白或仍为占位密钥时抛出，用于在<b>发起任何外部 HTTP
 * 请求之前</b>快速失败，避免无意义的鉴权调用与密钥外泄风险。</p>
 */
public class AiConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiConfigurationException(String message) {
        super(message);
    }
}
