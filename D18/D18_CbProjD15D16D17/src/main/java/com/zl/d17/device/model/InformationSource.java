package com.zl.d17.device.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 信息来源类型（D18）。
 *
 * <p>用于在候选中标注每条结论的可信来源，帮助区分用户原话、会话归纳与模型推断。</p>
 */
public enum InformationSource {

    /** 用户在对话中明确陈述的信息。 */
    USER_STATED,

    /** 由多轮会话内容归纳得到的信息（含规则匹配）。 */
    SESSION_INFERRED,

    /** 由模型推断得到的信息（本阶段 mock 链路不产生真实模型推断）。 */
    MODEL_INFERRED;

    /**
     * 容错解析：无法识别的取值统一归 {@link #MODEL_INFERRED} 并在不确定性说明中留痕。
     */
    @JsonCreator
    public static InformationSource fromValue(String value) {
        if (value == null) {
            return USER_STATED;
        }
        String normalized = value.trim().toUpperCase();
        for (InformationSource source : values()) {
            if (source.name().equals(normalized)) {
                return source;
            }
        }
        return MODEL_INFERRED;
    }
}
