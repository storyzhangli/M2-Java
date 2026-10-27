package com.zl.d14.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问日志脱敏、截断与结构化输出工具。
 *
 * <p>设计原则：</p>
 * <ul>
 *     <li>所有日志事件最终序列化为<b>单行 JSON</b>，天然转义换行/引号，防止日志伪造（Log Forging）；</li>
 *     <li>敏感字段按“小写包含”匹配，命中后值整体替换为占位符；</li>
 *     <li>任何正文都有长度上限，超限截断并显式标记 {@code truncated=true}；</li>
 *     <li>方法本身不抛异常：序列化/解析失败时回退为安全摘要，绝不影响业务返回。</li>
 * </ul>
 */
@Slf4j
public class LogMaskingUtils {

    /** 专用于日志的 Mapper：不开启 default typing，避免多态反序列化风险；空 Bean 不致失败。 */
    private final ObjectMapper logMapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final AccessLogProperties properties;

    public LogMaskingUtils(AccessLogProperties properties) {
        this.properties = properties;
    }

    /** 脱敏后的文本及是否发生截断。 */
    public record MaskedText(String text, boolean truncated) {
    }

    /**
     * 处理原始 JSON 字符串：先按字段名脱敏，再按最大长度截断。
     * 非法 JSON 不强行输出原文（可能携带密钥），返回带长度的不可解析占位说明。
     */
    public MaskedText prepareJsonBody(String raw) {
        if (raw == null) {
            return new MaskedText(null, false);
        }
        String safe;
        try {
            JsonNode tree = logMapper.readTree(raw);
            maskTree(tree);
            safe = logMapper.writeValueAsString(tree);
        } catch (Exception parseFail) {
            safe = "<unparseable body, length=" + raw.length() + ">";
        }
        return truncate(safe);
    }

    /**
     * 处理任意 Java 对象（方法入参、返回对象）：转 JSON 树后脱敏并截断。
     * 失败时回退为类名摘要，保证日志环节不抛异常。
     */
    public MaskedText prepareObject(Object value) {
        if (value == null) {
            return new MaskedText("null", false);
        }
        try {
            JsonNode tree = logMapper.valueToTree(value);
            maskTree(tree);
            return truncate(logMapper.writeValueAsString(tree));
        } catch (Exception e) {
            return new MaskedText("<unserializable: " + value.getClass().getName() + ">", false);
        }
    }

    /**
     * 脱敏请求头 Map：敏感头整体掩码，其余原值保留。
     */
    public Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) ->
                out.put(name, isSensitive(name, properties.getSensitiveHeaders())
                        ? properties.getMaskText() : value));
        return out;
    }

    /**
     * 脱敏 Query 字符串：按 k=v 拆分，敏感参数掩码；保持 application/x-www-form 风格可读。
     */
    public String sanitizeQuery(String queryString) {
        if (!StringUtils.hasText(queryString)) {
            return null;
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : queryString.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            if (isSensitive(key, properties.getSensitiveFields())) {
                pairs.add(key + "=" + properties.getMaskText());
            } else {
                pairs.add(pair);
            }
        }
        return String.join("&", pairs);
    }

    /**
     * 规整外部传入的 requestId：仅保留字母、数字、中划线与下划线，剔除换行等字符防日志注入；
     * 非法/空白返回 null，由调用方生成新 ID。
     */
    public String sanitizeInboundRequestId(String inbound) {
        if (!StringUtils.hasText(inbound)) {
            return null;
        }
        String cleaned = inbound.trim().replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** 判断 Content-Type 是否属于不应缓存/打印正文的二进制、文件或流式类型。 */
    public boolean isBinaryOrStreaming(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("multipart/")
                || lower.contains(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                || lower.startsWith("image/")
                || lower.startsWith("video/")
                || lower.startsWith("audio/")
                || lower.contains("text/event-stream")
                || lower.contains("attachment");
    }

    /** 将事件 Map 序列化为单行 JSON；任何异常都兜底为简单文本，不让日志影响主流程。 */
    public String toJsonLine(Map<String, Object> event) {
        try {
            return logMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"event\":\"LOG_SERIALIZE_ERROR\",\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    /** URL 解码的安全封装，失败时返回原值，不抛异常。 */
    public String decodeQuietly(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // ============================ 内部方法 ============================

    /** 递归遍历 JSON 树，对敏感字段就地替换为掩码（数组与嵌套对象均覆盖）。 */
    private void maskTree(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String name : fieldNames) {
                JsonNode child = objectNode.get(name);
                if (isSensitive(name, properties.getSensitiveFields())) {
                    objectNode.put(name, properties.getMaskText());
                } else {
                    maskTree(child);
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskTree);
        }
    }

    /** 名称小写后做“包含匹配”，兼容 apiKey / x-api-key / userPassword 等变体。 */
    private boolean isSensitive(String name, List<String> keywords) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return keywords.stream().anyMatch(lower::contains);
    }

    /** 按配置上限截断，并标注 truncated 与原始长度。 */
    private MaskedText truncate(String text) {
        if (text == null) {
            return new MaskedText(null, false);
        }
        int limit = properties.getMaxPayloadLength();
        if (text.length() <= limit) {
            return new MaskedText(text, false);
        }
        String cut = text.substring(0, limit) + "...<truncated,originLength=" + text.length() + ">";
        return new MaskedText(cut, true);
    }

    /** 供过滤器判断请求对象类型，避免记录 Servlet/ Spring 容器对象。 */
    public boolean isContainerArgument(Class<?> type) {
        if (type == null) {
            return false;
        }
        String name = type.getName();
        return name.startsWith("jakarta.servlet.")
                || name.startsWith("org.springframework.")
                || name.startsWith("org.springframework.web.")
                || name.startsWith("jakarta.");
    }

    /** 便捷读取字符集，默认 UTF-8。 */
    public String resolveCharset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        return StringUtils.hasText(encoding) ? encoding : StandardCharsets.UTF_8.name();
    }
}
