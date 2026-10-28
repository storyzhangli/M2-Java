package com.zl.d15.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d15.prompt.mapper.PromptTemplateMapper;
import com.zl.d15.prompt.model.PromptTemplateEntity;
import com.zl.d15.prompt.model.TemplateStatus;
import com.zl.d15.prompt.po.PromptTemplatePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 种子模板幂等初始化器。
 *
 * <p>启动阶段将 classpath 的种子 JSON 安全导入 MySQL，规则：</p>
 * <ul>
 *     <li>以业务唯一键 {@code template_id} 判存，已存在（含内容被人工修改、停用、逻辑删除）一律跳过，
 *     绝不用种子覆盖数据库中的运行期修改；</li>
 *     <li>只插入不存在的键，天然支持重复启动、重复执行而不产生重复数据（数据库唯一键为最终防线）；</li>
 *     <li>输出新增/跳过/失败计数日志；种子解析失败或插入失败直接抛出异常阻止应用带病启动，
 *     不吞异常、不误报成功。</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptSeedInitializer implements ApplicationRunner {

    /** 种子资源路径，与 D15 内存版保持同一文件，文件本身保留不删除。 */
    public static final String SEED_RESOURCE = "prompt-templates/seed-templates.json";

    private final PromptTemplateMapper mapper;

    private final ObjectMapper objectMapper;

    public PromptSeedInitializer(PromptTemplateMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
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
        int inserted = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();

        for (PromptTemplateEntity seed : seeds) {
            String templateId = seed.getTemplateId();
            if (templateId == null || templateId.isBlank()) {
                throw new IllegalStateException("种子模板存在缺失 templateId 的记录");
            }
            Long existing = mapper.selectCount(new LambdaQueryWrapper<PromptTemplatePO>()
                    .eq(PromptTemplatePO::getTemplateId, templateId));
            if (existing != null && existing > 0) {
                // 无论数据库中的内容/状态是否与种子不同，都跳过：运行期数据权威归数据库
                skipped++;
                continue;
            }
            try {
                mapper.insert(toPo(seed, now));
                inserted++;
            } catch (Exception e) {
                // 并发初始化或唯一键冲突等问题记录具体键名，事务回滚后由启动失败暴露
                failures.add(templateId + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Prompt 种子初始化存在失败记录: " + String.join("; ", failures));
        }
        log.info("Prompt 种子初始化完成，新增 {} 条，跳过（数据库已存在）{} 条，失败 0 条，资源={}",
                inserted, skipped, SEED_RESOURCE);
    }

    private PromptTemplatePO toPo(PromptTemplateEntity entity, LocalDateTime now) {
        PromptTemplatePO po = new PromptTemplatePO();
        po.setTemplateId(entity.getTemplateId());
        po.setName(entity.getName());
        po.setScene(entity.getScene());
        po.setDescription(entity.getDescription());
        po.setContent(entity.getContent());
        po.setVariables(entity.getVariables() == null ? new ArrayList<>() : entity.getVariables());
        po.setVersion(entity.getVersion() <= 0 ? 1 : entity.getVersion());
        po.setStatus(entity.getStatus() == null ? TemplateStatus.ENABLED.name() : entity.getStatus().name());
        po.setDeleted(0);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }
}
