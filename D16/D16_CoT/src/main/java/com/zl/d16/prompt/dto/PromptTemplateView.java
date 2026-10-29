package com.zl.d16.prompt.dto;

import com.zl.d16.prompt.model.PromptTemplateEntity;
import com.zl.d16.prompt.model.TemplateVariable;

import java.io.Serializable;
import java.util.List;

/**
 * 模板详情视图，含正文与完整变量声明。
 */
public record PromptTemplateView(
        String templateId,
        String name,
        String scene,
        String description,
        String content,
        List<TemplateVariable> variables,
        int version,
        String status,
        String createdAt,
        String updatedAt
) implements Serializable {

    /**
     * 由实体映射详情视图。
     */
    public static PromptTemplateView from(PromptTemplateEntity entity) {
        return new PromptTemplateView(
                entity.getTemplateId(),
                entity.getName(),
                entity.getScene(),
                entity.getDescription(),
                entity.getContent(),
                List.copyOf(entity.getVariables()),
                entity.getVersion(),
                entity.getStatus().name(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString()
        );
    }
}
