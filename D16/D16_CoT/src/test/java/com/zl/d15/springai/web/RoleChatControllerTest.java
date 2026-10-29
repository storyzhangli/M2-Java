package com.zl.d15.springai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d15.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同步角色对话 Web 层测试：会话标识回传、模板溯源、多轮历史累积、非法角色与变量错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleChatControllerTest extends AbstractPersistenceCleanupTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roleChatShouldReturnSessionAndTemplateMetadata() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "message", "我想咨询退款进度",
                "scene", "customer_service",
                "variables", Map.of("business_context", "售后窗口期内")
        ));

        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.scene").value("customer_service"))
                .andExpect(jsonPath("$.data.templateId").value("customer_service"))
                .andExpect(jsonPath("$.data.templateVersion").value(1))
                .andExpect(jsonPath("$.data.mocked").value(true));
    }

    @Test
    void secondTurnWithSameSessionShouldCarryHistoryAndKeepRoleIsolation() throws Exception {
        MvcResult firstResult = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "第一轮：虚拟线程是什么", "scene", "programming"))))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();
        assertThat(sessionId).hasSize(32);

        MvcResult secondResult = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "第二轮：它和平台线程区别",
                                "scene", "programming",
                                "sessionId", sessionId))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode second = objectMapper.readTree(secondResult.getResponse().getContentAsString());
        // Mock 回显中带历史条数：第二轮准备时会话内已有第一轮的 user+assistant 两条
        assertThat(second.path("data").path("content").asText()).contains("历史消息条数=2");

        // 同会话切角色必须被拒绝
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "换角色", "scene", "customer_service", "sessionId", sessionId))))
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void illegalSceneAndUnknownVariableShouldReturnBusinessError() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "非法角色", "scene", "not_a_role"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "非法变量",
                                "scene", "customer_service",
                                "variables", Map.of("hack_key", "v")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void legacyPathWithoutSceneShouldKeepOldResponseShape() throws Exception {
        // 不带 scene 时响应仍是旧的 ChatReplyResponse 结构，不出现会话字段
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "旧链路回归"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("mock"))
                .andExpect(jsonPath("$.data.sessionId").doesNotExist());
    }
}
