package com.zl.d17.springai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模板管理控制器 Web 层测试：角色清单、查询过滤、创建校验、更新版本自增、启停与逻辑删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromptTemplateControllerWebTest extends AbstractPersistenceCleanupTest {

    private static final String BASE = "/api/v1/ai";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rolesEndpointShouldReturnFourEnabledChatRoles() throws Exception {
        mockMvc.perform(get(BASE + "/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[?(@.scene=='customer_service')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.scene=='programming')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.scene=='device_customer_service')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.scene=='technical_engineer')]").isNotEmpty());
    }

    @Test
    void promptsListShouldFilterByScene() throws Exception {
        // 不断言精确条数：共享上下文内其他测试可能创建同 scene 的自建模板，种子模板必须稳定在结果中
        mockMvc.perform(get(BASE + "/prompts").param("scene", "code_review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.templateId=='code_review')].variableCount").value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test
    void createWithUndeclaredPlaceholderShouldReturnBusinessError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "templateId", "ut_web_bad",
                "name", "Web坏模板",
                "scene", "summarization",
                "content", "含未声明占位符 {ghost}",
                "variables", List.of()
        ));
        mockMvc.perform(post(BASE + "/prompts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void fullLifecycleCreateUpdateToggleDeleteShouldWork() throws Exception {
        String id = "ut_web_lifecycle";
        String createBody = objectMapper.writeValueAsString(Map.of(
                "templateId", id,
                "name", "Web生命周期模板",
                "scene", "technical_explanation",
                "content", "面向 {audience} 讲解",
                "variables", List.of(Map.of(
                        "name", "audience", "required", false,
                        "description", "受众", "defaultValue", "初学者"))
        ));
        mockMvc.perform(post(BASE + "/prompts").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "templateId", id,
                "name", "Web生命周期模板v2",
                "scene", "technical_explanation",
                "content", "更新后面向 {audience} 讲解",
                "variables", List.of(Map.of(
                        "name", "audience", "required", false,
                        "description", "受众", "defaultValue", "初学者"))
        ));
        mockMvc.perform(put(BASE + "/prompts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(patch(BASE + "/prompts/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(patch(BASE + "/prompts/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ENABLED"))))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        mockMvc.perform(delete(BASE + "/prompts/" + id))
                .andExpect(jsonPath("$.data.deleted").value("true"));

        mockMvc.perform(get(BASE + "/prompts/" + id))
                .andExpect(jsonPath("$.code").value(4001));
    }
}
