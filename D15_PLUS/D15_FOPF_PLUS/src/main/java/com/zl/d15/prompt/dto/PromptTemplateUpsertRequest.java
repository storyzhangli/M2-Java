package com.zl.d15.prompt.dto;

import com.zl.d15.prompt.model.TemplateVariable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板创建/更新请求体。
 *
 * <p>创建时 templateId 必填且全局唯一；更新时以路径参数为准，请求体内的 templateId 仅做一致性校验。
 * Bean Validation 负责基础非空/长度约束，占位符与变量声明的业务一致性由服务层校验。</p>
 */
@Data
public class PromptTemplateUpsertRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板稳定唯一标识，字母开头，允许字母数字下划线与中划线，最长 64 位。 */
    @NotBlank(message = "templateId 不能为空")
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}", message = "templateId 需以字母开头，仅含字母数字、下划线或中划线")
    private String templateId;

    /** 模板名称。 */
    @NotBlank(message = "name 不能为空")
    @Size(max = 100, message = "name 长度不能超过100个字符")
    private String name;

    /** 场景标识，必须属于 PromptScenes.ALL_SCENES。 */
    @NotBlank(message = "scene 不能为空")
    @Size(max = 64, message = "scene 长度不能超过64个字符")
    private String scene;

    /** 用途说明。 */
    @Size(max = 500, message = "description 长度不能超过500个字符")
    private String description;

    /** 模板正文。 */
    @NotBlank(message = "content 不能为空或纯空白")
    @Size(max = 8000, message = "content 长度不能超过8000个字符")
    private String content;

    /** 变量声明列表，可为空（无变量模板）；嵌套触发逐条 Bean Validation。 */
    @Valid
    private List<TemplateVariable> variables = new ArrayList<>();
}
