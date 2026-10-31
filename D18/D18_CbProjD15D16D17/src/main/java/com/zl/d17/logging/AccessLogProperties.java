package com.zl.d17.logging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 访问日志（AOP 请求/响应日志）外部化配置，前缀 {@code app.access-log}。
 *
 * <p>所有脱敏关键字、截断长度、排除路径均可在 {@code application.yml} 覆盖；
 * 代码内给出安全默认值，做到“开箱即脱敏、超限即截断”。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.access-log")
public class AccessLogProperties {

    /** 总开关：关闭后切面与过滤器整体不装配（请求标识透传也一并关闭）。 */
    private boolean enabled = true;

    /** 请求/响应中承载 requestId 的请求头名称，同时回写到响应头便于调用方关联。 */
    private String requestIdHeader = "X-Request-Id";

    /** 单段正文（请求体/响应体/方法入参/返回对象）允许打印的最大字符数，超限截断并标记 truncated=true。 */
    private int maxPayloadLength = 2048;

    /** 是否记录必要请求头（敏感头仍会脱敏，与该开关无关）。 */
    private boolean includeHeaders = true;

    /** 是否记录请求正文（JSON/文本）；二进制、multipart、流式始终只记录元数据。 */
    private boolean includeRequestBody = true;

    /** 是否记录响应正文；二进制、流式始终只记录元数据。 */
    private boolean includeResponseBody = true;

    /** 是否在 Controller 切面记录绑定后的方法入参。 */
    private boolean includeMethodArgs = true;

    /** 不记录访问日志的路径（Ant 风格），但 requestId 仍会正常生成与透传。 */
    private List<String> excludePaths = new ArrayList<>(List.of("/error", "/favicon.ico"));

    /** 敏感请求头（小写匹配、包含即命中），命中后值整体脱敏。 */
    private List<String> sensitiveHeaders = new ArrayList<>(List.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-access-token"));

    /** 敏感 JSON 字段 / Query 参数名（小写匹配、包含即命中），命中后值整体脱敏。 */
    private List<String> sensitiveFields = new ArrayList<>(List.of(
            "password", "passwd", "pwd", "token", "secret", "credential",
            "apikey", "api-key", "authorization", "cookie",
            "idcard", "id-card", "bankcard", "bank-card", "cardno"));

    /** 脱敏占位符。 */
    private String maskText = "*****";
}
