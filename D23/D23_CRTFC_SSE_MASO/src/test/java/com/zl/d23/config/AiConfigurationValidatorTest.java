package com.zl.d23.config;

import com.zl.d23.common.AiConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * AI 配置校验器测试：覆盖 mock 无 Key 启动、真实模式缺配置快速失败与非法基址等场景。
 * 不启动 Spring 上下文、不触外网。
 */
class AiConfigurationValidatorTest {

    private AiProperties properties(String mode, String model, String baseUrl, String apiKey) {
        AiProperties props = new AiProperties();
        props.setMode(mode);
        props.setModel(model);
        props.setBaseUrl(baseUrl);
        props.setApiKey(apiKey);
        return props;
    }

    @Test
    @DisplayName("mock 模式：无 Key 也能通过校验")
    void mockModeRequiresNoKey() {
        AiProperties props = properties(AiConstants.MODE_MOCK,
                "deepseek-flash", "https://api.deepseek.com", "");

        assertThatCode(() -> new AiConfigurationValidator(props).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deepseek 模式：配置齐全时通过校验")
    void deepseekModeValid() {
        AiProperties props = properties(AiConstants.MODE_DEEPSEEK,
                "deepseek-flash", "https://api.deepseek.com", "sk-real-secret");

        assertThatCode(() -> new AiConfigurationValidator(props).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deepseek 模式：Key 空白/占位/含 mock 均拒绝启动")
    void deepseekModeRejectsInvalidKey() {
        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, "m", "https://x.com", "")).validate())
                .isInstanceOf(AiConfigurationException.class);

        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, "m", "https://x.com",
                        AiConstants.PLACEHOLDER_API_KEY)).validate())
                .isInstanceOf(AiConfigurationException.class);

        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, "m", "https://x.com",
                        "some-mock-value")).validate())
                .isInstanceOf(AiConfigurationException.class);
    }

    @Test
    @DisplayName("deepseek 模式：模型名空白拒绝启动")
    void deepseekModeRejectsBlankModel() {
        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, " ", "https://x.com", "sk-real")).validate())
                .isInstanceOf(AiConfigurationException.class);
    }

    @Test
    @DisplayName("deepseek 模式：Base URL 非 http/https 或非法拒绝启动")
    void deepseekModeRejectsBadBaseUrl() {
        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, "m", "ftp://x.com", "sk-real")).validate())
                .isInstanceOf(AiConfigurationException.class);

        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties(AiConstants.MODE_DEEPSEEK, "m", "not-a-url", "sk-real")).validate())
                .isInstanceOf(AiConfigurationException.class);
    }

    @Test
    @DisplayName("非法 mode：既不是 mock 也不是 deepseek 时拒绝启动")
    void illegalModeRejected() {
        assertThatThrownBy(() -> new AiConfigurationValidator(
                properties("azure", "m", "https://x.com", "k")).validate())
                .isInstanceOf(AiConfigurationException.class);
    }

    @Test
    @DisplayName("异常消息不回显真实 Key")
    void errorMessageDoesNotLeakKey() {
        String secret = "sk-super-secret-123456";
        try {
            new AiConfigurationValidator(
                    properties(AiConstants.MODE_DEEPSEEK, "m", "https://x.com", " ")).validate();
        } catch (AiConfigurationException error) {
            assertThat(error.getMessage()).doesNotContain(secret);
        }
    }
}
