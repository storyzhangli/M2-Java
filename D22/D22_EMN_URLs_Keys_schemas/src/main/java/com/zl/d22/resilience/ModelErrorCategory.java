package com.zl.d22.resilience;

import com.zl.d22.common.ResultCode;
import lombok.Getter;

/**
 * 模型调用异常的统一内部分类。
 *
 * <p>这是同步与流式两条链路共用的“单一事实来源”：所有模型调用抛出的异常，先由
 * {@link ModelExceptionClassifier} 归一到本枚举，再据此决定是否重试以及对外映射的
 * {@link ResultCode}。新增异常类型时只需扩展分类器，对外契约与重试口径保持不变。</p>
 *
 * <p>记忆简语：参数鉴权不重试，限流五xx可重试，建连可重试、读超时默认不重试，
 * 取消未知走兜底。</p>
 */
@Getter
public enum ModelErrorCategory {

    /** 请求参数/报文错误（HTTP 400、非法参数、Bean Validation 校验失败）。 */
    PARAM_ERROR(false, ResultCode.PARAM_INVALID),

    /** 鉴权/授权失败（HTTP 401/403、密钥缺失或占位）。 */
    AUTH_ERROR(false, ResultCode.AI_CONFIG_ERROR),

    /** 模型或资源不存在（HTTP 404）。 */
    MODEL_NOT_FOUND(false, ResultCode.AI_CONFIG_ERROR),

    /** 内容策略/安全拒绝（HTTP 409/422 等不可重试的请求语义错误）。 */
    CONTENT_POLICY_REJECT(false, ResultCode.AI_CONFIG_ERROR),

    /** 限流（HTTP 429），上游通常可恢复，需尊重 Retry-After 响应头。 */
    RATE_LIMIT_RECOVERABLE(true, ResultCode.AI_UNAVAILABLE),

    /** 上游服务暂不可用（HTTP 500/502/503/504 等服务端错误）。 */
    UPSTREAM_UNAVAILABLE(true, ResultCode.AI_UNAVAILABLE),

    /** 建连失败（连接拒绝、未知主机、建连超时、TLS 握手失败），请求尚未发出，可安全重试。 */
    CONNECT_FAILURE(true, ResultCode.AI_UNAVAILABLE),

    /** 读取超时（请求已发出、等待响应超时），结果不确定，默认不自动重试以避免重复计费。 */
    READ_TIMEOUT(false, ResultCode.AI_UNAVAILABLE),

    /** 响应解析/校验失败（响应体无法映射为预期结构）。 */
    RESPONSE_PARSE_ERROR(false, ResultCode.AI_CONFIG_ERROR),

    /** 客户端主动取消或断开连接。 */
    CLIENT_CANCELLED(false, ResultCode.SYSTEM_ERROR),

    /** 未归类的未知异常，保守按不可重试处理。 */
    UNKNOWN(false, ResultCode.SYSTEM_ERROR);

    /** 该分类在默认口径下是否允许有限重试。 */
    private final boolean retryable;

    /** 重试耗尽或不可重试时，对外返回的统一业务码。 */
    private final ResultCode resultCode;

    ModelErrorCategory(boolean retryable, ResultCode resultCode) {
        this.retryable = retryable;
        this.resultCode = resultCode;
    }
}
