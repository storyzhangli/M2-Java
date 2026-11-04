package com.zl.d22.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型调用记录器（Model Call Recorder）。
 *
 * <p>负责为每次逻辑模型调用创建一个请求级 {@link ModelCallObservation}，并把最终的
 * {@link ModelCallRecord} 序列化为<b>单行 JSON</b> 输出到独立 logger（名称 {@code MODEL_CALL}）。
 * 单行 JSON 天然转义换行与引号，可防止日志注入，也便于日志采集系统按行解析。</p>
 *
 * <p>本类只做观测，不参与重试、超时等业务决策，也不向外抛异常；
 * 序列化失败时回退为安全摘要，绝不影响模型调用主流程。</p>
 */
@Slf4j
@Component
public class ModelCallRecorder {

    /** 独立 logger 名称，可在 logback 配置中单独控制输出目标与级别。 */
    public static final String CALL_LOGGER_NAME = "MODEL_CALL";

    /** 同步调用模式标识。 */
    public static final String MODE_SYNC = "SYNC";

    /** 流式调用模式标识。 */
    public static final String MODE_STREAM = "STREAM";

    private final Logger callLogger = LoggerFactory.getLogger(CALL_LOGGER_NAME);

    /** 专用 Mapper：不开启 default typing，空 Bean 不报错。 */
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final ModelObservabilityProperties properties;

    public ModelCallRecorder(ModelObservabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * 开始一次模型调用观测。
     *
     * @param provider Provider 名称（如 deepseek）
     * @param model    实际生效的模型名
     * @param callMode 调用模式：{@link #MODE_SYNC} 或 {@link #MODE_STREAM}
     * @return 请求级观测上下文
     */
    public ModelCallObservation begin(String provider, String model, String callMode) {
        return new ModelCallObservation(this, properties, provider, model, callMode);
    }

    /**
     * 输出终态记录：序列化为单行 JSON；任何异常都兜底，不影响主流程。
     */
    public void emit(ModelCallRecord record) {
        try {
            callLogger.info(mapper.writeValueAsString(record.toOrderedMap()));
        } catch (Exception serializeError) {
            callLogger.info("{\"event\":\"MODEL_CALL\",\"outcome\":\""
                    + record.outcome() + "\",\"error\":\"LOG_SERIALIZE_ERROR\"}");
        }
    }

    /** 是否启用观测（供观测上下文在关闭时短路）。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
