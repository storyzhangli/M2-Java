package com.zl.d17.device.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 故障工单分类候选枚举（D18）。
 *
 * <p>分类用于工单候选，不直接写正式工单；任何无法确认的取值都收敛到 {@link #UNKNOWN}，
 * 不强行归类、不臆造。</p>
 */
public enum TicketCategory {

    /** 电气类（供电、电气线路、配电相关）。 */
    ELECTRICAL,

    /** 机械类（传动、卡滞、机械结构相关）。 */
    MECHANICAL,

    /** 冷却/液压类（冷却系统、液位、液压、泄漏相关）。 */
    COOLING,

    /** 控制/伺服类（控制器、伺服、编码器、位置偏差相关）。 */
    CONTROL,

    /** 传感器类（温度、压力等传感器及测量相关）。 */
    SENSOR,

    /** 安全类（安全联锁、防护、冒烟等高危情形）。 */
    SAFETY,

    /** 未知/待确认：信息不足或取值无法识别时统一归此值。 */
    UNKNOWN;

    /**
     * 容错解析：空白或无法识别的字符串统一返回 {@link #UNKNOWN}，保证反序列化不抛异常。
     */
    @JsonCreator
    public static TicketCategory fromValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase();
        for (TicketCategory category : values()) {
            if (category.name().equals(normalized)) {
                return category;
            }
        }
        return UNKNOWN;
    }
}
