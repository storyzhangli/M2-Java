package com.zl.d17.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.prompt.model.TemplateStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存模板仓储（D15 初代实现，D15_PLUS 已停用）。
 *
 * <p><b>历史实现说明：</b>D15 无数据库依赖时，本类在启动时从 classpath 的
 * {@code prompt-templates/seed-templates.json} 加载种子并以内存作为权威存储，
 * 重启会丢失全部运行期修改。D15_PLUS 起权威存储迁移至 MySQL
 * （见 {@link MybatisPromptTemplateRepository}），种子初始化改由
 * {@code PromptSeedInitializer} 幂等执行，因此本类不再注册为 Spring Bean，
 * 仅保留代码作为内存版方案的可审查参考，禁止在新代码中注入使用。</p>
 */
@Slf4j
@Deprecated
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
