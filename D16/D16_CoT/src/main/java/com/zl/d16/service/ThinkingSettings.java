package com.zl.d16.service;

/**
 * 一次请求经过服务端校验后的思考模式最终设置（服务器权威配置，前端标签不作数）。
 *
 * <p>该对象由 {@link ThinkingSettingsResolver} 根据请求 DTO 与应用能力白名单生成，
 * 模型适配层只允许读取本对象，不直接信任前端原始字符串。</p>
 *
 * @param enabled       是否启用思考模式；false 时走普通模型链路，保持旧客户端行为
 * @param effort        推理强度；当前 Spring AI 1.1.8 无法下发，非空入参已在解析阶段被拒绝，
 *                      因此正常流程中该值始终为 {@code null}，保留字段作为版本升级后的扩展位
 * @param showReasoning 是否向调用方受控展示原始推理全文；默认 false，推理内容不进入普通答案/日志/会话记忆
 */
public record ThinkingSettings(boolean enabled, ReasoningEffort effort, boolean showReasoning) {

    /** 旧客户端缺省设置：思考关闭、不展示推理内容，行为与 D16 之前版本完全一致。 */
    public static ThinkingSettings legacyDefault() {
        return new ThinkingSettings(false, null, false);
    }
}
