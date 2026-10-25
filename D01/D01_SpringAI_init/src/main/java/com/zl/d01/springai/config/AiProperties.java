package com.zl.d01.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D01 应用自定义 AI 配置（前缀 {@code app.ai}），与 Spring AI 官方的
 * {@code spring.ai.deepseek.*} 前缀严格区分。
 *
 * <ul>
 *     <li>{@code mode}：运行模式，取值 {@code mock}（默认）或 {@code deepseek}，由环境变量
 *         {@code AI_MODE} 注入；</li>
 *     <li>{@code maxMessageLength}：聊天入参消息长度上限，可通过 {@code AI_CHAT_MAX_LENGTH}
 *         调整，避免无上限请求。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 运行模式：mock（离线模拟）或 deepseek（真实调用），默认 mock。 */
    private String mode = AiConstants.MODE_MOCK;

    /** 消息最大长度（字符数），默认 2000，可通过配置覆盖。 */
    private int maxMessageLength = 2000;
}
