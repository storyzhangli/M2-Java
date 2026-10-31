package com.zl.d17.prompt.dto;

import com.zl.d17.prompt.model.PromptTemplateEntity;

import java.io.Serializable;

/**
 * 模板列表项视图（不含正文与变量细节，减少列表载荷）。
 */
public record PromptTemplateSummary(
        String templateId,
        String name,
        String scene,
        String description,
        int version,
        String status,
        int variableCount,
        String updatedAt
) implements Serializable {

    /**
     * 由实体映射列表视图。
     */
    public static PromptTemplateSummary from(PromptTemplateEntity entity) {
        int count = entity.getVariables() == null ? 0 : entity.getVariables().size();
        return new PromptTemplateSummary(
                entity.getTemplateId(),
                entity.getName(),
                entity.getScene(),
                entity.getDescription(),
                entity.getVersion(),
                entity.getStatus().name(),
                count,
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString()
        );
    }
}
