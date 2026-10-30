package com.zl.d17.prompt.model;

/**
 * Prompt 模板启停状态。
 *
 * <p>仅启用（{@link #ENABLED}）状态的模板允许被角色对话选中；停用（{@link #DISABLED}）后模板数据保留，
 * 已有历史会话仍可通过 templateId/version 追溯，但新对话不得再选用。</p>
 */
public enum TemplateStatus {

    /** 启用：可被查询详情、角色对话选用。 */
    ENABLED,

    /** 停用：仅可管理端查看，不能发起新的模型调用。 */
    DISABLED
}
