package com.zl.d15.springai.prompt;

import com.zl.d16.prompt.PromptTemplateService;
import com.zl.d16.prompt.dto.PromptTemplateSummary;
import com.zl.d16.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d16.prompt.dto.PromptTemplateView;
import com.zl.d16.prompt.model.PromptTemplateEntity;
import com.zl.d16.prompt.model.TemplateStatus;
import com.zl.d16.prompt.model.TemplateVariable;
import com.zl.d15.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prompt 模板领域服务测试：覆盖种子加载、生命周期、变量/占位符校验、ST4 字面大括号转义与渲染规则。
 *
 * <p>使用完整 Spring 上下文（真实 MySQL 仓库 + Redis 缓存 + 幂等种子初始化），写操作作用于
 * ut_ 前缀的测试自建模板，跨用例数据清理由 {@link AbstractPersistenceCleanupTest} 统一负责；
 * 必须改动种子状态的用例在 finally 中恢复，避免污染共享上下文里的其他测试。</p>
 */
@SpringBootTest
class PromptTemplateServiceTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private PromptTemplateService service;

    @Test
    void seedTemplatesShouldLoadSixScenesAndTwoChatRoles() {
        // 不断言总数精确等于 6：测试类共享同一 Spring 上下文，其他用例自建模板会留在内存仓库中
        List<PromptTemplateSummary> all = service.list(null, null, null);
        assertThat(all).extracting(PromptTemplateSummary::templateId)
                .contains("customer_service", "programming", "technical_explanation",
                        "code_review", "summarization", "information_extraction");

        // 角色清单只可能来自两个内置角色场景，自建模板均使用非角色场景，数量恒为 2
        List<PromptTemplateSummary> roles = service.listEnabledRoles();
        assertThat(roles).extracting(PromptTemplateSummary::scene)
                .containsExactly("customer_service", "programming");
    }

    @Test
    void everySeedTemplateShouldRenderWithDeclaredVariables() {
        // 对全部种子模板做一次真实 ST4 渲染（必填变量给桩值，可选走默认值），覆盖启动后首次使用的可用性
        for (PromptTemplateSummary summary : service.list(null, null, null)) {
            PromptTemplateEntity entity = loadEntity(summary.templateId());
            java.util.Map<String, Object> inputs = new java.util.HashMap<>();
            entity.getVariables().stream()
                    .filter(TemplateVariable::required)
                    .forEach(v -> inputs.put(v.name(), "STUB"));
            String rendered = service.render(entity, inputs);
            assertThat(rendered).isNotBlank();
            // 渲染结果不得残留 ST4 转义符或未替换占位符
            assertThat(rendered).doesNotContain("\\{", "\\}");
            assertThat(rendered).doesNotContain("{source_text}", "{schema_hint}");
        }
    }

    @Test
    void listShouldSupportSceneStatusAndKeywordFilters() {
        // programming 为内置独占场景，测试自建模板不使用该 scene，过滤结果稳定为 1
        assertThat(service.list("programming", null, null)).hasSize(1);
        assertThat(service.list(null, "ENABLED", null))
                .extracting(PromptTemplateSummary::templateId)
                .contains("customer_service", "programming");
        assertThat(service.list(null, null, "客服")).extracting(PromptTemplateSummary::templateId)
                .containsExactly("customer_service");
        assertThatThrownBy(() -> service.list(null, "BAD_STATUS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailOfMissingTemplateShouldReject() {
        assertThatThrownBy(() -> service.detail("not_exists_template"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("不存在或已删除");
    }

    @Test
    void createUpdateAndLogicalDeleteShouldFollowVersionAndVisibilityRules() {
        String id = "ut_lifecycle_tpl";
        PromptTemplateUpsertRequest create = baseRequest(id, "单元测试模板", "summarization",
                "总结规则，变量：{source_text}",
                List.of(new TemplateVariable("source_text", true, "原文", null)));

        PromptTemplateView created = service.create(create);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.status()).isEqualTo(TemplateStatus.ENABLED.name());

        PromptTemplateUpsertRequest update = baseRequest(id, "单元测试模板-改", "summarization",
                "更新后的总结规则：{source_text}",
                List.of(new TemplateVariable("source_text", true, "原文", null)));
        assertThat(service.update(id, update).version()).isEqualTo(2);
        assertThat(service.detail(id).name()).isEqualTo("单元测试模板-改");

        // 重复创建同 id 必须拒绝（即使后续被逻辑删除也复用同一判定口径）
        assertThatThrownBy(() -> service.create(create))
                .isInstanceOf(IllegalArgumentException.class);

        service.logicalDelete(id);
        assertThat(service.list(null, null, null)).extracting(PromptTemplateSummary::templateId)
                .doesNotContain(id);
        assertThatThrownBy(() -> service.detail(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void undeclaredPlaceholderShouldBeRejected() {
        PromptTemplateUpsertRequest req = baseRequest("ut_bad_placeholder", "坏模板", "summarization",
                "正文出现未声明变量 {ghost}", List.of());
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("未声明的变量");
    }

    @Test
    void rawJsonBracesMustBeEscapedAndEscapedFormRendersLiteralBraces() {
        // 裸 JSON 大括号：ST4 会解析失败，预检要给出转义提示而不是晦涩的引擎异常
        PromptTemplateUpsertRequest raw = baseRequest("ut_raw_brace", "裸括号模板", "code_review",
                "输出结构：{\"defects\":[]}", List.of());
        assertThatThrownBy(() -> service.create(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("转义");

        // 转义形式可通过保存校验，且渲染后输出字面大括号（与 code_review 种子模板同规则）
        PromptTemplateUpsertRequest escaped = baseRequest("ut_escaped_brace", "转义括号模板", "code_review",
                "输出：\\{\"ok\":true\\}", List.of());
        service.create(escaped);
        PromptTemplateEntity entity = loadEntity("ut_escaped_brace");
        String rendered = service.render(entity, Map.of());
        assertThat(rendered).contains("{\"ok\":true}").doesNotContain("\\{");
    }

    @Test
    void requiredVariableMissingAndUnknownInputKeyShouldRejectAtRender() {
        PromptTemplateEntity summarization = service.list("summarization", null, null).stream()
                .findFirst().map(s -> loadEntity(s.templateId())).orElseThrow();

        // 必填的 source_text 缺失且无默认值
        assertThatThrownBy(() -> service.render(summarization, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("缺少必填模板变量");

        // 未声明的入参键拒绝，防止借变量位注入额外内容
        assertThatThrownBy(() -> service.render(summarization, Map.of("source_text", "原文", "hack", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("未声明的变量");

        // 正常渲染：必填值进入正文，可选缺省值自动兜底
        String rendered = service.render(summarization, Map.of("source_text", "这是一段待总结原文"));
        assertThat(rendered).contains("这是一段待总结原文").contains("300");
    }

    @Test
    void roleTemplateResolutionShouldRejectNonRoleSceneAndDisabledTemplate() {
        assertThatThrownBy(() -> service.resolveEnabledRoleTemplate("code_review"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("不是当前开放的对话角色");

        try {
            service.changeStatus("customer_service", "DISABLED");
            assertThatThrownBy(() -> service.resolveEnabledRoleTemplate("customer_service"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().contains("已停用");
        } finally {
            // 无论断言结果如何都恢复种子状态，避免污染其他测试
            service.changeStatus("customer_service", "ENABLED");
        }
    }

    private PromptTemplateEntity loadEntity(String templateId) {
        PromptTemplateView view = service.detail(templateId);
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setTemplateId(view.templateId());
        entity.setName(view.name());
        entity.setScene(view.scene());
        entity.setDescription(view.description());
        entity.setContent(view.content());
        entity.setVariables(view.variables());
        entity.setVersion(view.version());
        entity.setStatus(TemplateStatus.valueOf(view.status()));
        return entity;
    }

    private PromptTemplateUpsertRequest baseRequest(String id, String name, String scene,
                                                    String content, List<TemplateVariable> variables) {
        PromptTemplateUpsertRequest req = new PromptTemplateUpsertRequest();
        req.setTemplateId(id);
        req.setName(name);
        req.setScene(scene);
        req.setDescription("单元测试用模板");
        req.setContent(content);
        req.setVariables(variables);
        return req;
    }
}
