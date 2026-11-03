package com.zl.d21.observability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型调用结构化终态记录（Model Call Record）。
 *
 * <p>这是写日志前的最终载体，字段严格采用<b>白名单</b>：只包含关联 ID、Provider、模型、调用模式、
 * 起止时间、单调时钟耗时、终态、安全错误码、usage 状态/来源/数值与 Provider response id。
 * 记录中不包含 API Key、Authorization、Cookie、完整 Prompt、用户原文或完整响应。</p>
 *
 * <p>缺失的数值保留为 null（而非 0），与 {@link UsageStatus} 一起表达“没有拿到数据”，
 * 因此 JSON 中保留 null 字段是有意为之，便于审计时直接看到缺口。</p>
 */
public record ModelCallRecord(String requestId,
                              String traceId,
                              String provider,
                              String model,
                              String callMode,
                              String startTime,
                              String endTime,
                              long durationMs,
                              CallOutcome outcome,
                              String errorCode,
                              UsageStatus usageStatus,
                              UsageSource usageSource,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              String responseId,
                              Long timeToFirstChunkMs) {

    /** 事件类型标识，写在每条记录最前面，便于日志采集与过滤。 */
    public static final String EVENT_TYPE = "MODEL_CALL";

    /**
     * 转换为有序 Map（保证字段顺序稳定），再由记录器序列化为单行 JSON。
     *
     * <p>使用 LinkedHashMap 固定白名单顺序；null 值保留，显式表达字段缺失。</p>
     */
    public Map<String, Object> toOrderedMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", EVENT_TYPE);
        map.put("requestId", requestId);
        map.put("traceId", traceId);
        map.put("provider", provider);
        map.put("model", model);
        map.put("callMode", callMode);
        map.put("startTime", startTime);
        map.put("endTime", endTime);
        map.put("durationMs", durationMs);
        map.put("outcome", outcome);
        map.put("errorCode", errorCode);
        map.put("usageStatus", usageStatus);
        map.put("usageSource", usageSource);
        map.put("promptTokens", promptTokens);
        map.put("completionTokens", completionTokens);
        map.put("totalTokens", totalTokens);
        map.put("responseId", responseId);
        map.put("timeToFirstChunkMs", timeToFirstChunkMs);
        return map;
    }
}
