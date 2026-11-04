package com.zl.d22.config;

import com.zl.d22.common.AiConfigurationException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

/**
 * 应用 AI 配置校验器（AI Configuration Validator）。
 *
 * <p>在 Bean 初始化阶段（{@link PostConstruct}）对 {@code app.ai.*} 做<b>快速失败</b>校验，
 * 保证错误配置在应用启动时就被发现，而不是等到首次真实调用才暴露，更不会静默回退：</p>
 * <ul>
 *     <li>{@code mode} 必须是 {@code mock} 或 {@code deepseek}；</li>
 *     <li>{@code deepseek} 模式：{@code api-key} 必须非空白且不是占位/含 mock 的串，
 *         {@code model} 必须非空白，{@code base-url} 必须是合法的 http/https URI；</li>
 *     <li>{@code mock} 模式：不要求密钥，无 Key 即可启动。</li>
 * </ul>
 *
 * <p>所有错误消息都不回显密钥，避免凭据泄漏到启动日志。</p>
 */
@Component
public class AiConfigurationValidator {

    private final AiProperties aiProperties;

    public AiConfigurationValidator(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    public void validate() {
        String mode = aiProperties.getMode();
        if (!AiConstants.MODE_MOCK.equals(mode) && !AiConstants.MODE_DEEPSEEK.equals(mode)) {
            throw new AiConfigurationException(
                    "app.ai.mode 取值非法：仅支持 mock 或 deepseek，当前值已阻止启动");
        }

        if (AiConstants.MODE_DEEPSEEK.equals(mode)) {
            validateRemoteMode();
        }
    }

    private void validateRemoteMode() {
        String apiKey = aiProperties.getApiKey();
        if (!StringUtils.hasText(apiKey)
                || AiConstants.PLACEHOLDER_API_KEY.equals(apiKey)
                || apiKey.toLowerCase(Locale.ROOT).contains("mock")) {
            throw new AiConfigurationException(
                    "deepseek 真实模式未配置有效密钥：AI_API_KEY 缺失、为空白或仍为占位密钥，应用已拒绝启动");
        }

        if (!StringUtils.hasText(aiProperties.getModel())) {
            throw new AiConfigurationException(
                    "deepseek 真实模式未配置模型名：AI_MODEL 缺失或为空白，应用已拒绝启动");
        }

        if (!StringUtils.hasText(aiProperties.getBaseUrl())) {
            throw new AiConfigurationException(
                    "deepseek 真实模式未配置服务基址：AI_BASE_URL 缺失或为空白，应用已拒绝启动");
        }

        URI uri;
        try {
            uri = URI.create(aiProperties.getBaseUrl());
        } catch (IllegalArgumentException error) {
            throw new AiConfigurationException(
                    "deepseek 真实模式 AI_BASE_URL 不是合法 URI，应用已拒绝启动");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new AiConfigurationException(
                    "deepseek 真实模式 AI_BASE_URL 必须以 http 或 https 开头，应用已拒绝启动");
        }
    }
}
