package com.zl.d17.springai.device;

import com.zl.d17.prompt.PromptTemplateRepository;
import com.zl.d17.prompt.PromptTemplateService;
import com.zl.d17.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d17.prompt.dto.PromptTemplateView;
import com.zl.d17.prompt.model.PromptScenes;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.prompt.model.TemplateVariable;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备场景模板选择与版本化测试（T01–T04）。
 *
 * <p>T01/T02 验证两个设备场景解析到正确的启用模板；T03 验证更新后版本自增；
 * T04 验证必填变量缺失时渲染快速失败。自建模板均以 ut_ 前缀，由清理基类回收。</p>
 */
@SpringBootTest
class DeviceTemplateSelectionTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private PromptTemplateRepository promptTemplateRepository;

    @Test
    void t01_deviceCustomerServiceSceneShouldResolveSeedTemplate() {
        PromptTemplateEntity entity =
                promptTemplateService.resolveEnabledRoleTemplate(PromptScenes.DEVICE_CUSTOMER_SERVICE);
        assertThat(entity.getTemplateId()).isEqualTo("device_customer_service");
        assertThat(entity.getScene()).isEqualTo(PromptScenes.DEVICE_CUSTOMER_SERVICE);
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void t02_technicalEngineerSceneShouldResolveSeedTemplate() {
        PromptTemplateEntity entity =
                promptTemplateService.resolveEnabledRoleTemplate(PromptScenes.TECHNICAL_ENGINEER);
        assertThat(entity.getTemplateId()).isEqualTo("technical_engineer");
        assertThat(entity.getScene()).isEqualTo(PromptScenes.TECHNICAL_ENGINEER);
        assertThat(entity.getVersion()).isEqualTo(1);
    }

    @Test
    void t03_updateTemplateShouldIncrementVersionAndRefreshUpdatedAt() {
        String templateId = "ut_tpl_version_device";
        PromptTemplateView created = promptTemplateService.create(buildUpsert(templateId,
                PromptScenes.DEVICE_CUSTOMER_SERVICE, "第一版正文，无变量。", List.of()));
        assertThat(created.version()).isEqualTo(1);
        java.time.LocalDateTime createdTime =
                promptTemplateRepository.findActiveById(templateId).orElseThrow().getUpdatedAt();

        PromptTemplateView updated = promptTemplateService.update(templateId, buildUpsert(templateId,
                PromptScenes.DEVICE_CUSTOMER_SERVICE, "第二版正文，补充澄清规则，无变量。", List.of()));
        assertThat(updated.version()).isEqualTo(2);
        java.time.LocalDateTime updatedTime =
                promptTemplateRepository.findActiveById(templateId).orElseThrow().getUpdatedAt();
        assertThat(updatedTime).isAfterOrEqualTo(createdTime);

        PromptTemplateView updatedAgain = promptTemplateService.update(templateId, buildUpsert(templateId,
                PromptScenes.DEVICE_CUSTOMER_SERVICE, "第三版正文，继续演进，无变量。", List.of()));
        assertThat(updatedAgain.version()).isEqualTo(3);
    }

    @Test
    void t04_missingRequiredVariableShouldFailRendering() {
        String templateId = "ut_tpl_required_device";
        TemplateVariable required = new TemplateVariable("device_model", true, "设备型号，必填", null);
        promptTemplateService.create(buildUpsert(templateId,
                PromptScenes.TECHNICAL_ENGINEER,
                "技术工程师模板，设备型号为：{device_model}", List.of(required)));

        PromptTemplateEntity own = promptTemplateRepository.findActiveById(templateId).orElseThrow();

        assertThatThrownBy(() -> promptTemplateService.render(own, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填模板变量");

        assertThat(promptTemplateService.render(own, Map.of("device_model", "CNC-8800")))
                .contains("CNC-8800");
    }

    private PromptTemplateUpsertRequest buildUpsert(String templateId, String scene,
                                                     String content, List<TemplateVariable> variables) {
        PromptTemplateUpsertRequest request = new PromptTemplateUpsertRequest();
        request.setTemplateId(templateId);
        request.setName(templateId + "-name");
        request.setScene(scene);
        request.setDescription("设备场景测试模板");
        request.setContent(content);
        request.setVariables(variables);
        return request;
    }
}
