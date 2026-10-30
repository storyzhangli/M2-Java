package com.zl.d17.history.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话历史与上下文窗口配置（前缀 {@code app.ai.history}）。
 *
 * <p>窗口按消息条数控制而非 Token 预算：本项目没有 Token 计数能力，
 * 因此不声称精确 Token 控制，所有参数均支持环境变量外部化覆盖。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.history")
public class HistoryProperties {

    /** 单次模型请求允许携带的最近历史消息条数（用户与助手合计），默认 10 条（约 5 个问答轮次）。 */
    private int contextWindowMessages = 10;

    /** 历史查询接口默认分页大小。 */
    private int defaultPageSize = 20;

    /** 开发身份请求头名称；项目无真实认证，仅用于开发环境的归属隔离，不是鉴权凭据。 */
    private String devUserHeader = "X-Dev-User";

    /** 请求未携带开发身份头时的默认归属标识。 */
    private String defaultOwner = "dev-user";

    /** 会话标题取首条用户消息的最大截断字符数。 */
    private int titleMaxLength = 50;
}
