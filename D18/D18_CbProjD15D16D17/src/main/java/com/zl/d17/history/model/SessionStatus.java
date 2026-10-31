package com.zl.d17.history.model;

/**
 * 会话状态。
 */
public enum SessionStatus {

    /** 活跃会话，可继续对话。 */
    ACTIVE,

    /** 已关闭会话，仅保留历史查询用途。 */
    CLOSED
}
