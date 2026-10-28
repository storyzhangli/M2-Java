package com.zl.d15.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d15.prompt.model.PromptTemplateEntity;
import com.zl.d15.prompt.model.TemplateStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存模板仓储。
 *
 * <p>启动时从 classpath 的 {@code prompt-templates/seed-templates.json} 加载内置种子模板，
 * 不依赖数据库或任何外部存储。运行期的增删改停留在内存，应用重启后恢复为种子数据，
 * 这是当前无持久化模块下的明确取舍（见 D15 技术文档“持久化方案”章节）。</p>
 */
@Slf4j
@Repository
public class InMemoryPromptTemplateRepository implements PromptTemplateRepository {

    /** 种子模板资源路径，随应用一起打包。 */
    public static final String SEED_RESOURCE = "prompt-templates/seed-templates.json";

    private final ConcurrentHashMap<String, PromptTemplateEntity> store = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public InMemoryPromptTemplateRepository(ObjectMapper objectMapper) {
        // 复用 Spring Boot 自动配置的 ObjectMapper（含 JSR-310 等模块），避免手工 new 造成模块不一致
        this.objectMapper = objectMapper;
    }

    /**
     * 加载并校验种子数据。种子损坏属于启动级故障，直接抛出异常阻止应用带病启动。
     */
    @PostConstruct
    void loadSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt 种子模板资源不存在: " + SEED_RESOURCE);
        }
        List<PromptTemplateEntity> seeds;
        try (InputStream in = resource.getInputStream()) {
            seeds = objectMapper.readValue(in, new TypeReference<List<PromptTemplateEntity>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析 Prompt 种子模板失败: " + e.getMessage(), e);
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException("Prompt 种子模板为空，至少需要内置场景模板");
        }

        LocalDateTime now = LocalDateTime.now();
        for (PromptTemplateEntity entity : seeds) {
            if (entity.getTemplateId() == null || entity.getTemplateId().isBlank()) {
                throw new IllegalStateException("种子模板存在缺失 templateId 的记录");
            }
            if (store.putIfAbsent(entity.getTemplateId(), entity) != null) {
                throw new IllegalStateException("种子模板 templateId 重复: " + entity.getTemplateId());
            }
            if (entity.getStatus() == null) {
                entity.setStatus(TemplateStatus.ENABLED);
            }
            if (entity.getVariables() == null) {
                entity.setVariables(new ArrayList<>());
            }
            entity.setDeleted(false);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
        }
        log.info("Prompt 种子模板加载完成，共 {} 个，资源={}", store.size(), SEED_RESOURCE);
    }

    @Override
    public List<PromptTemplateEntity> findAllActive() {
        return store.values().stream()
                .filter(e -> !e.isDeleted())
                .sorted(Comparator.comparing(PromptTemplateEntity::getTemplateId))
                .toList();
    }

    @Override
    public Optional<PromptTemplateEntity> findActiveById(String templateId) {
        return Optional.ofNullable(store.get(templateId))
                .filter(e -> !e.isDeleted());
    }

    @Override
    public Optional<PromptTemplateEntity> findAnyById(String templateId) {
        return Optional.ofNullable(store.get(templateId));
    }

    @Override
    public PromptTemplateEntity save(PromptTemplateEntity entity) {
        store.put(entity.getTemplateId(), entity);
        return entity;
    }
}
