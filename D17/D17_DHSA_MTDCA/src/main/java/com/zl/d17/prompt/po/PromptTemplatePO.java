package com.zl.d17.prompt.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.prompt.model.TemplateVariable;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 模板表 {@code prompt_template} 的 MyBatis-Plus 持久化对象。
 *
 * <p>PO 只负责与数据库行结构映射，不直接暴露给 Controller；与领域模型
 * {@link PromptTemplateEntity} 之间由仓储实现负责转换，
 * 使上层服务不感知 ORM 与 JSON 列细节。{@code variables} 使用 MP 内置
 * {@link JacksonTypeHandler} 与 MySQL JSON 列互转，autoResultMap 必须开启才会生效。</p>
 */
@Data
@NoArgsConstructor
@TableName(value = "prompt_template", autoResultMap = true)
public class PromptTemplatePO {

    /** 代理主键，数据库自增。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务稳定标识，数据库层有唯一约束 uk_template_id 兜底。 */
    private String templateId;

    private String name;

    private String scene;

    private String description;

    private String content;

    /** 变量声明列表，落库为 JSON 数组；typeHandler 负责 List 与 JSON 的双向转换。 */
    @TableField(value = "variables", typeHandler = JacksonTypeHandler.class)
    private List<TemplateVariable> variables = new ArrayList<>();

    private Integer version;

    /** ENABLED / DISABLED，以字符串存储，避免枚举序号演进带来的兼容问题。 */
    private String status;

    /** 逻辑删除标志：0 未删除 1 已删除。 */
    private Integer deleted;

    /** 时间戳由仓储层从领域实体显式写入，不依赖自动填充处理器，保证内存版与持久化版行为一致。 */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
