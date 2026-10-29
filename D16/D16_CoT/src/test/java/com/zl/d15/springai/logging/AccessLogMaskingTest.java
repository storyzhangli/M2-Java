package com.zl.d15.springai.logging;

import com.zl.d16.logging.AccessLogProperties;
import com.zl.d16.logging.LogMaskingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏、截断与类型判定的纯单元测试（不启动 Spring 容器）。
 */
class AccessLogMaskingTest {

    private AccessLogProperties properties;
    private LogMaskingUtils utils;

    @BeforeEach
    void setUp() {
        properties = new AccessLogProperties();
        utils = new LogMaskingUtils(properties);
    }

    @Test
    void sensitiveJsonFieldsShouldBeMaskedIncludingNestedAndArray() {
        String raw = "{\"message\":\"hi\",\"password\":\"p-123\",\"user\":{\"apiKey\":\"sk-abc\"},\"items\":[{\"token\":\"t-9\"}]}";

        LogMaskingUtils.MaskedText masked = utils.prepareJsonBody(raw);

        assertThat(masked.text()).doesNotContain("p-123", "sk-abc", "t-9");
        assertThat(masked.text()).contains(properties.getMaskText());
        // 非敏感字段保留
        assertThat(masked.text()).contains("hi");
        assertThat(masked.truncated()).isFalse();
    }

    @Test
    void sensitiveHeaderAndQueryShouldBeMasked() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer secret");
        headers.put("Content-Type", "application/json");
        Map<String, String> safe = utils.sanitizeHeaders(headers);
        assertThat(safe.get("Authorization")).isEqualTo("*****");
        assertThat(safe.get("Content-Type")).isEqualTo("application/json");

        String query = utils.sanitizeQuery("page=1&token=abc&password=pwd");
        assertThat(query).contains("token=*****", "password=*****").contains("page=1");
    }

    @Test
    void overlongBodyShouldBeTruncatedAndMarked() {
        properties.setMaxPayloadLength(20);
        String longJson = "{\"message\":\"" + "x".repeat(200) + "\"}";
        LogMaskingUtils.MaskedText masked = utils.prepareJsonBody(longJson);
        assertThat(masked.truncated()).isTrue();
        assertThat(masked.text()).contains("truncated");
    }

    @Test
    void malformedJsonShouldFallbackToSafePlaceholderWithoutLeakingRaw() {
        String malformed = "{ broken password=leak-secret";
        LogMaskingUtils.MaskedText masked = utils.prepareJsonBody(malformed);
        assertThat(masked.text()).startsWith("<unparseable body");
        assertThat(masked.text()).doesNotContain("leak-secret");
    }

    @Test
    void binaryAndStreamingContentTypeShouldBeDetected() {
        assertThat(utils.isBinaryOrStreaming("multipart/form-data; boundary=xx")).isTrue();
        assertThat(utils.isBinaryOrStreaming("application/octet-stream")).isTrue();
        assertThat(utils.isBinaryOrStreaming("text/event-stream")).isTrue();
        assertThat(utils.isBinaryOrStreaming("application/json")).isFalse();
    }

    @Test
    void inboundRequestIdShouldStripForgingCharacters() {
        assertThat(utils.sanitizeInboundRequestId("abc-123\n\r\nfake-line")).isEqualTo("abc-123fake-line");
        assertThat(utils.sanitizeInboundRequestId("   ")).isNull();
    }

    @Test
    void containerArgumentShouldBeRecognized() {
        assertThat(utils.isContainerArgument(jakarta.servlet.http.HttpServletRequest.class)).isTrue();
        assertThat(utils.isContainerArgument(String.class)).isFalse();
    }

    @Test
    void objectSerializationShouldMaskSensitivePojoField() {
        record LoginForm(String username, String password) {}
        LogMaskingUtils.MaskedText masked = utils.prepareObject(new LoginForm("alice", "secret-pwd"));
        assertThat(masked.text()).contains("alice").doesNotContain("secret-pwd").contains("*****");
    }
}
