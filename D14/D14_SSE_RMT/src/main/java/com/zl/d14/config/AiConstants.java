package com.zl.d14.config;

/**
 * D01 AI 接入相关常量。
 *
 * <p>集中管理模式标识、占位密钥与模拟回复标记，避免在业务代码与配置文件中出现散落的魔法值。</p>
 */
public final class AiConstants {

    /** 模拟模式：默认值，不发起任何真实网络请求，直接返回确定性结果。 */
    public static final String MODE_MOCK = "mock";

    /** 真实模式：通过 Spring AI 调用 DeepSeek 官方接口。 */
    public static final String MODE_DEEPSEEK = "deepseek";

    /**
     * 模拟占位密钥。
     *
     * <p>该串仅用于本地无密钥环境下让 Spring AI 自动配置顺利装配 Bean，<b>显式无效、绝不可能通过
     * DeepSeek 真实鉴权</b>；真实模式下若仍为该值会在调用前被主动拦截。</p>
     */
    public static final String PLACEHOLDER_API_KEY = "d01-mock-key-not-for-authentication";

    /** 模拟回复的固定标记，便于测试与调用方一眼识别当前为离线模拟结果。 */
    public static final String MOCK_REPLY_MARKER = "[MOCK:D01]";

    private AiConstants() {
    }
}
