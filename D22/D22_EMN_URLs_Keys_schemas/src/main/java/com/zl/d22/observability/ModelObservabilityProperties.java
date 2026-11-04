package com.zl.d22.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型调用可观测性外部化配置，前缀 {@code app.model-observability}。
 *
 * <p>所有开关均给出安全默认值，可在 {@code application.yml} 或通过环境变量覆盖；
 * 本配置只控制“是否写终态记录、是否记录首 Chunk 延迟”，不改变任何业务调用与对外接口契约。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.model-observability")
public class ModelObservabilityProperties {

    /**
     * 总开关：关闭后不再写模型调用结构化终态记录（不影响 TraceIdFilter 与访问日志）。
     * 可用环境变量 MODEL_OBSERVABILITY_ENABLED=false 覆盖。
     */
    private boolean enabled = true;

    /**
     * 是否记录首个 Chunk 延迟（Time To First Chunk，首块到达时间）。
     * 只有在能准确测量（流确实发射过至少一个分片）时才会写出该值，否则字段缺省。
     */
    private boolean recordTimeToFirstChunk = true;
}
