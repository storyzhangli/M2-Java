package com.zl.d16.prompt.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Prompt 模板变量声明。
 *
 * <p>模板正文使用 Spring AI（StringTemplate 4，ST4）单大括号占位符语法 {@code {变量名}}，
 * 每个允许出现的占位符都必须在变量声明列表中登记，做到“先声明、后使用”。</p>
 *
 * @param name         变量名，仅允许字母开头的字母数字下划线，最长 32 位
 * @param required     是否必填；非必填变量缺失时使用 {@link #defaultValue()}，无默认值则按空串处理
 * @param description  变量用途说明，便于管理端理解与维护
 * @param defaultValue 默认值（可为 null）；必填变量的默认值不参与兜底
 */
public record TemplateVariable(

        @NotBlank(message = "变量名不能为空")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,31}", message = "变量名需以字母开头且仅含字母数字下划线，最长32位")
        String name,

        boolean required,

        @Size(max = 200, message = "变量说明不能超过200个字符")
        String description,

        @Size(max = 500, message = "变量默认值不能超过500个字符")
        String defaultValue
) implements Serializable {
}
