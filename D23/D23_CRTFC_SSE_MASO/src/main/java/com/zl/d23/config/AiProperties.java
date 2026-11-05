package com.zl.d23.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级 AI 配置（前缀 {@code app.ai}），D23 起作为模型连接的<b>唯一配置源</b>。
 *
 * <p>承载运行模式（{@code mode}）、模型名（{@code model}）、服务基址（{@code base-url}）、
 * API 密钥（{@code api-key}）、单条消息长度上限（{@code max-message-length}）
 * 与流式响应超时（{@code stream-timeout-ms}）。对应环境变量统一为
 * {@code AI_MODE / AI_MODEL / AI_BASE_URL / AI_API_KEY}，不再使用供应商专名前缀。</p>
 *
 * <p>本类只做绑定与默认值，不包含任何供应商专有类型；真实模型客户端在适配层依据这些字段构造。
 * 通过 {@code @ConfigurationPropertiesScan} 注册，无需再声明 {@code @Component}。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 运行模式：{@code mock}（默认，离线模拟）或 {@code deepseek}（真实调用）。 */
    private String mode = AiConstants.MODE_MOCK;

    /** 实际生效的模型名（Model Name），由适配层在每次调用时下发；默认 {@code deepseek-flash}。 */
    private String model = "deepseek-flash";

    /** 模型服务基址（Base URL），可指向官方网关或私有代理；默认 DeepSeek 官方基址。 */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * API 密钥（API Key），仅真实模式需要；默认空字符串。禁止硬编码真实密钥，
     * 必须通过环境变量 {@code AI_API_KEY} 或启动参数注入。
     */
    private String apiKey = "";

    /** 单条用户消息的最大长度（字符数），默认 2000，超出后由控制器直接拒绝。 */
    private int maxMessageLength = 2000;

    /**
     * SSE 流式连接的服务端超时时间（毫秒），默认 60 秒。
     *
     * <p>超时后容器会触发 {@code SseEmitter.onTimeout}，服务端据此取消上游订阅、释放连接，
     * 可通过 {@code AI_STREAM_TIMEOUT_MS} 调整。</p>
     */
    private long streamTimeoutMs = 60000L;
}
