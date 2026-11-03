package com.zl.d21;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求标识（requestId / traceId）真机 HTTP 测试。
 *
 * <p>在嵌入式 Tomcat 上验证外部 ID 的复用、缺失生成、非法字符清洗与防串号，
 * 对应任务书关于“外部 Header 校验长度/格式防注入、请求结束清理防串号”的口径。
 * 默认 mock 模式，不触外网。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TraceIdHttpTest {

    private static final String HEADER = "X-Request-Id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern GENERATED_ID = Pattern.compile("[A-Za-z0-9]{32}");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("外部合法 ID 被原样复用并回写响应头")
    void reuseInboundId() throws Exception {
        HttpResponse<String> response = send("trace-abc-123");
        assertThat(response.headers().firstValue(HEADER)).hasValue("trace-abc-123");
    }

    @Test
    @DisplayName("缺失 ID 时生成 32 位无横线安全 ID")
    void generateWhenMissing() throws Exception {
        HttpResponse<String> response = send(null);
        String generated = response.headers().firstValue(HEADER).orElse("");
        assertThat(generated).hasSize(32);
        assertThat(GENERATED_ID.matcher(generated).matches()).isTrue();
    }

    @Test
    @DisplayName("含空格与特殊字符的 ID 被清洗，只保留安全字符，防止日志注入")
    void sanitizeUnsafeChars() throws Exception {
        HttpResponse<String> response = send("good-123 evil!");
        String returned = response.headers().firstValue(HEADER).orElse("");
        assertThat(returned).isEqualTo("good-123evil");
        assertThat(SAFE_ID.matcher(returned).matches()).isTrue();
    }

    @Test
    @DisplayName("全为非法字符的 ID 清洗后为空，重新生成而非回显非法值")
    void regenerateWhenAllUnsafe() throws Exception {
        HttpResponse<String> response = send("! !");
        String returned = response.headers().firstValue(HEADER).orElse("");
        assertThat(returned).hasSize(32);
        assertThat(GENERATED_ID.matcher(returned).matches()).isTrue();
    }

    @Test
    @DisplayName("连续两个无 ID 请求得到不同标识，验证线程复用下不串号")
    void distinctIdsAcrossRequests() throws Exception {
        String first = send(null).headers().firstValue(HEADER).orElse("");
        String second = send(null).headers().firstValue(HEADER).orElse("");
        assertThat(first).isNotEqualTo(second);
    }

    private HttpResponse<String> send(String requestId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"你好\"}"));
        if (requestId != null) {
            builder.header(HEADER, requestId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
