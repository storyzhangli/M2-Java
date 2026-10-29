package com.zl.d16.prompt;

import com.zl.d16.prompt.model.PromptTemplateEntity;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 模板仓储抽象。
 *
 * <p>本迭代提供基于 {@code ConcurrentHashMap} 的内存实现；接口化的目的是让上层服务不感知存储介质，
 * 后续接入数据库时只需新增一个实现类，Service 与 Controller 无需改动。</p>
 */
public interface PromptTemplateRepository {

    /**
     * 查询全部未逻辑删除的模板（返回快照集合，调用方遍历不受并发写入影响）。
     */
    List<PromptTemplateEntity> findAllActive();

    /**
     * 按稳定标识查询未删除模板。
     */
    Optional<PromptTemplateEntity> findActiveById(String templateId);

    /**
     * 按稳定标识查询，包含已逻辑删除的模板，仅供创建去重等内部判断使用。
     */
    Optional<PromptTemplateEntity> findAnyById(String templateId);

    /**
     * 新增或覆盖保存模板。
     */
    PromptTemplateEntity save(PromptTemplateEntity entity);
}
