package com.zl.d15.springai.prompt;

import com.zl.d16.prompt.PromptSeedInitializer;
import com.zl.d16.prompt.PromptTemplateRepository;
import com.zl.d16.prompt.PromptTemplateService;
import com.zl.d16.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d16.prompt.dto.PromptTemplateView;
import com.zl.d16.prompt.model.TemplateVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 权威存储 + Redis cache-aside 的集成验证。
 *
 * <p>前置条件：本机 MySQL(ai_db.prompt_template) 与 Redis 可用；覆盖种子幂等不覆盖、
 * 缓存回源/命中/失效/空标记/清空后恢复等关键路径。</p>
 */
@SpringBootTest
class PersistenceCacheIntegrationTest {

    private static final String TPL_ID = "ut_cache_tpl";
    private static final String ENTITY_KEY = "d15f:prompt:tpl:" + TPL_ID;
    private static final String SEED_KEY = "d15f:prompt:tpl:customer_service";

    @Autowired
    private PromptSeedInitializer seedInitializer;

    @Autowired
    private PromptTemplateRepository repository;

    @Autowired
    private PromptTemplateService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM prompt_template WHERE template_id LIKE 'ut_%'");
        redis.delete(List.of(ENTITY_KEY, ENTITY_KEY + ":null", SEED_KEY, SEED_KEY + ":null",
                "d15f:prompt:tpl:ut_not_exists_xyz",
                "d15f:prompt:tpl:ut_not_exists_xyz:null"));
        jdbcTemplate.update("UPDATE prompt_template SET name = '客服对话助手' WHERE template_id = 'customer_service'");
    }

    @Test
    void seedInitializerShouldBeIdempotentAndNeverOverwriteDatabaseChanges() throws Exception {
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prompt_template WHERE template_id IN "
                        + "('customer_service','programming','technical_explanation','code_review',"
                        + "'summarization','information_extraction')", Integer.class);
        assertThat(before).isEqualTo(6);

        // 再执行两次初始化（模拟重复启动），六个种子数量保持不变
        seedInitializer.run(null);
        seedInitializer.run(null);
        Integer afterTwice = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prompt_template WHERE template_id IN "
                        + "('customer_service','programming','technical_explanation','code_review',"
                        + "'summarization','information_extraction')", Integer.class);
        assertThat(afterTwice).isEqualTo(6);

        // 人工修改种子内容后再次初始化，数据库修改必须保留，不允许被 classpath JSON 覆盖
        jdbcTemplate.update("UPDATE prompt_template SET name = '人工改名-客服' WHERE template_id = 'customer_service'");
        seedInitializer.run(null);
        String name = jdbcTemplate.queryForObject(
                "SELECT name FROM prompt_template WHERE template_id = 'customer_service'", String.class);
        assertThat(name).isEqualTo("人工改名-客服");
    }

    @Test
    void readThroughShouldBackfillCacheAndEvictAfterUpdate() {
        redis.delete(List.of(SEED_KEY, SEED_KEY + ":null"));
        assertThat(redis.hasKey(SEED_KEY)).isFalse();

        // 首次读：缓存未命中，回源 MySQL 并回填
        assertThat(repository.findActiveById("customer_service")).isPresent();
        assertThat(redis.hasKey(SEED_KEY)).isTrue();
        Long ttl = redis.getExpire(SEED_KEY);
        assertThat(ttl).isNotNull().isGreaterThan(0);
        assertThat(redis.opsForValue().get(SEED_KEY)).contains("customer_service");

        // 清空 Redis 后仍能从 MySQL 恢复（缓存不是权威存储）
        redis.delete(SEED_KEY);
        assertThat(repository.findActiveById("customer_service")).isPresent();
        assertThat(redis.hasKey(SEED_KEY)).isTrue();

        // 不存在的模板写入空值标记，抑制穿透
        assertThat(repository.findActiveById("ut_not_exists_xyz")).isEmpty();
        assertThat(redis.hasKey("d15f:prompt:tpl:ut_not_exists_xyz:null")).isTrue();

        // 更新模板后缓存必须被失效，下次读重新回源
        PromptTemplateUpsertRequest create = new PromptTemplateUpsertRequest();
        create.setTemplateId(TPL_ID);
        create.setName("缓存失效测试");
        create.setScene("summarization");
        create.setDescription("it");
        create.setContent("总结：{source_text}");
        create.setVariables(List.of(new TemplateVariable("source_text", true, "原文", null)));
        service.create(create);
        repository.findActiveById(TPL_ID);
        assertThat(redis.hasKey(ENTITY_KEY)).isTrue();

        PromptTemplateUpsertRequest update = new PromptTemplateUpsertRequest();
        update.setTemplateId(TPL_ID);
        update.setName("缓存失效测试-改");
        update.setScene("summarization");
        update.setDescription("it");
        update.setContent("更新总结：{source_text}");
        update.setVariables(List.of(new TemplateVariable("source_text", true, "原文", null)));
        PromptTemplateView updated = service.update(TPL_ID, update);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(redis.hasKey(ENTITY_KEY)).isFalse();
    }
}
