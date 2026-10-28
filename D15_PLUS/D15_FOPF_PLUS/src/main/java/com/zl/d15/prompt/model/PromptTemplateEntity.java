package com.zl.d15.prompt.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 模板实体（内存仓库版本）。
 *
 * <p>当前模块没有数据库/ORM 依赖，实体由 Jackson 从 classpath 种子 JSON 加载并保存在内存仓库中；
 * 字段职责对齐任务书要求的模板数据模型，未来迁移到数据库时可直接映射为表结构。</p>
 *
 * <p>版本与删除策略：</p>
 * <ul>
 *     <li>{@link #version}：每次内容/变量被修改后自增，对话日志记录调用时的版本，问题可追溯；</li>
 *     <li>{@link #deleted}：逻辑删除标志，删除操作只置位不物理移除，正在被历史会话引用也可追溯；</li>
 *     <li>{@link #status}：启停状态，停用模板不允许被新对话选用。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class PromptTemplateEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 稳定唯一标识，创建后不可修改（如 customer_service）。 */
    private String templateId;

    /** 模板展示名称。 */
    private String name;

    /** 场景/角色标识，取值见 {@code PromptScenes} 常量，用于角色绑定与列表筛选。 */
    private String scene;

    /** 用途说明。 */
    private String description;

    /** Prompt 模板正文，占位符遵循 ST4 的 {变量名} 语法，字面大括号需转义为 \\{ \\}。 */
    private String content;

    /** 允许在正文中使用的变量声明列表，顺序即管理端展示顺序。 */
    private List<TemplateVariable> variables = new ArrayList<>();

    /** 模板版本，种子初始为 1，每次更新 +1。 */
    private int version = 1;

    /** 启停状态。 */
    private TemplateStatus status = TemplateStatus.ENABLED;

    /** 逻辑删除标志：true 表示已删除，不出现在任何查询/选择结果中。 */
    private boolean deleted = false;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
