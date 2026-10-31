package com.zl.d17.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.config.PromptCacheProperties;
import com.zl.d17.prompt.mapper.PromptTemplateMapper;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.prompt.model.TemplateStatus;
import com.zl.d17.prompt.po.PromptTemplatePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MySQL（MyBatis-Plus）+ Redis cache-aside 的模板仓储实现。
 *
 * <p>职责边界：</p>
 * <ul>
 *     <li>MySQL 是权威存储，创建/更新/启停/删除都先落库，缓存只存模板详情读副本；</li>
 *     <li>读路径未命中缓存时回源 MySQL 并回填，空结果用短 TTL 标记抑制缓存穿透；</li>
 *     <li>写路径在数据库成功后删除对应缓存（含空标记），不做复杂双删/分布式锁，
 *     与当前单实例、管理端低频写的规模匹配；</li>
 *     <li>Redis 故障时读操作降级回源 MySQL 并告警；写库成功后的缓存失效失败只告警，
 *     不伪造结果也不影响数据库权威写入。</li>
 * </ul>
 */
@Slf4j
@Repository
public class MybatisPromptTemplateRepository implements PromptTemplateRepository {

    private final PromptTemplateMapper mapper;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final PromptCacheProperties cacheProperties;

    public MybatisPromptTemplateRepository(PromptTemplateMapper mapper,
                                           StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           PromptCacheProperties cacheProperties) {
        this.mapper = mapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheProperties = cacheProperties;
    }

    @Override
    public List<PromptTemplateEntity> findAllActive() {
        // 列表走数据库索引 idx_scene_status_deleted，不对可变列表结果做缓存，避免多过滤条件下的失效复杂度
        LambdaQueryWrapper<PromptTemplatePO> wrapper = new LambdaQueryWrapper<PromptTemplatePO>()
                .eq(PromptTemplatePO::getDeleted, 0)
                .orderByAsc(PromptTemplatePO::getTemplateId);
        return mapper.selectList(wrapper).stream().map(this::toEntity).toList();
    }

    @Override
    public Optional<PromptTemplateEntity> findActiveById(String templateId) {
        Optional<PromptTemplateEntity> cached = readCache(templateId);
        if (cached != null) {
            return cached;
        }
        PromptTemplatePO po = selectAnyPo(templateId);
        if (po == null || po.getDeleted() != null && po.getDeleted() == 1) {
            writeNullMarker(templateId);
            return Optional.empty();
        }
        PromptTemplateEntity entity = toEntity(po);
        writeEntityCache(templateId, entity);
        return Optional.of(entity);
    }

    @Override
    public Optional<PromptTemplateEntity> findAnyById(String templateId) {
        // 创建去重需要看到已逻辑删除的记录，该内部判断不经过缓存，直接查库保证最新
        return Optional.ofNullable(selectAnyPo(templateId)).map(this::toEntity);
    }

    @Override
    public PromptTemplateEntity save(PromptTemplateEntity entity) {
        PromptTemplatePO existing = selectAnyPo(entity.getTemplateId());
        PromptTemplatePO po = toPo(entity);
        if (existing == null) {
            mapper.insert(po);
        } else {
            // 稳定标识不可改，按已有代理主键更新，version/deleted/created_at 均以领域实体当前值为准
            po.setId(existing.getId());
            int rows = mapper.updateById(po);
            if (rows != 1) {
                throw new IllegalStateException("模板更新影响行数异常，templateId=" + entity.getTemplateId()
                        + "，rows=" + rows);
            }
        }
        evictCache(entity.getTemplateId());
        return entity;
    }

    // ------------------------------------------------------------------
    // 缓存辅助方法：所有 Redis 异常就地兜底，不阻断主流程，MySQL 始终可独立提供服务
    // ------------------------------------------------------------------

    /**
     * 读缓存。返回值三态：null 表示缓存无数据需要回源；Optional.empty 表示命中空值标记；
     * Optional.of 表示命中实体副本。
     */
    private Optional<PromptTemplateEntity> readCache(String templateId) {
        if (!cacheProperties.isEnabled()) {
            return null;
        }
        try {
            String entityKey = entityKey(templateId);
            String json = redisTemplate.opsForValue().get(entityKey);
            if (json != null) {
                PromptTemplateEntity entity =
                        objectMapper.readValue(json, PromptTemplateEntity.class);
                // 滑动续期：热点 Key 的 TTL 在每次命中时刷新
                redisTemplate.expire(entityKey, Duration.ofSeconds(cacheProperties.getTtlSeconds()));
                return Optional.of(entity);
            }
            if (Boolean.TRUE.equals(redisTemplate.hasKey(nullKey(templateId)))) {
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("读取 Prompt 模板缓存失败，降级回源 MySQL，templateId={}，原因={}",
                    templateId, e.getClass().getSimpleName());
        }
        return null;
    }

    private void writeEntityCache(String templateId, PromptTemplateEntity entity) {
        if (!cacheProperties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(entityKey(templateId),
                    objectMapper.writeValueAsString(entity),
                    Duration.ofSeconds(cacheProperties.getTtlSeconds()));
            redisTemplate.delete(nullKey(templateId));
        } catch (Exception e) {
            log.warn("回填 Prompt 模板缓存失败，不影响数据库结果，templateId={}，原因={}",
                    templateId, e.getClass().getSimpleName());
        }
    }

    private void writeNullMarker(String templateId) {
        if (!cacheProperties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(nullKey(templateId), "1",
                    Duration.ofSeconds(cacheProperties.getNullTtlSeconds()));
        } catch (Exception e) {
            log.warn("写入空值标记失败，templateId={}，原因={}", templateId, e.getClass().getSimpleName());
        }
    }

    private void evictCache(String templateId) {
        if (!cacheProperties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.delete(List.of(entityKey(templateId), nullKey(templateId)));
        } catch (Exception e) {
            // 数据库已经是权威最新值；缓存最多残留至 TTL 到期，记录告警便于人工排查
            log.warn("失效 Prompt 模板缓存失败，将等待 TTL 自然过期，templateId={}，原因={}",
                    templateId, e.getClass().getSimpleName());
        }
    }

    private String entityKey(String templateId) {
        return cacheProperties.getKeyPrefix() + templateId;
    }

    private String nullKey(String templateId) {
        return cacheProperties.getKeyPrefix() + templateId + cacheProperties.getNullKeySuffix();
    }

    private PromptTemplatePO selectAnyPo(String templateId) {
        return mapper.selectOne(new LambdaQueryWrapper<PromptTemplatePO>()
                .eq(PromptTemplatePO::getTemplateId, templateId)
                .last("LIMIT 1"));
    }

    // ------------------------------------------------------------------
    // PO <-> 领域实体转换
    // ------------------------------------------------------------------

    private PromptTemplateEntity toEntity(PromptTemplatePO po) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setTemplateId(po.getTemplateId());
        entity.setName(po.getName());
        entity.setScene(po.getScene());
        entity.setDescription(po.getDescription());
        entity.setContent(po.getContent());
        entity.setVariables(po.getVariables() == null ? new ArrayList<>() : po.getVariables());
        entity.setVersion(po.getVersion() == null ? 1 : po.getVersion());
        entity.setStatus(TemplateStatus.valueOf(po.getStatus()));
        entity.setDeleted(po.getDeleted() != null && po.getDeleted() == 1);
        entity.setCreatedAt(po.getCreatedAt());
        entity.setUpdatedAt(po.getUpdatedAt());
        return entity;
    }

    private PromptTemplatePO toPo(PromptTemplateEntity entity) {
        PromptTemplatePO po = new PromptTemplatePO();
        po.setTemplateId(entity.getTemplateId());
        po.setName(entity.getName());
        po.setScene(entity.getScene());
        po.setDescription(entity.getDescription());
        po.setContent(entity.getContent());
        po.setVariables(entity.getVariables());
        po.setVersion(entity.getVersion());
        po.setStatus(entity.getStatus().name());
        po.setDeleted(entity.isDeleted() ? 1 : 0);
        po.setCreatedAt(entity.getCreatedAt());
        po.setUpdatedAt(entity.getUpdatedAt());
        return po;
    }
}
