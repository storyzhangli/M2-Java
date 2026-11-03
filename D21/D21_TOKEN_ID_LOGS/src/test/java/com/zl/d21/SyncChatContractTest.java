package com.zl.d21;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同步接口契约测试（T01/T02）。
 *
 * <p>验证新增流式接口后，原同步接口 {@code POST /api/v1/ai/chat} 的路径、方法、
 * Result 包装、业务码与错误语义保持零破坏。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncChatContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("T01 普通同步对话返回 200 且结构兼容")
    void normalSync_returnsCompatibleResult() throws Exception {
        String body = "{\"message\":\"你好，请介绍一下你自己\"}";
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.content").exists())
                .andExpect(jsonPath("$.data.mode").value("mock"))
                .andExpect(jsonPath("$.data.model").value("offline-mock"))
                .andExpect(jsonPath("$.data.mocked").value(true))
                .andExpect(jsonPath("$.data.sessionId").exists());
    }

    @Test
    @DisplayName("T01 角色同步对话返回 RoleChatReply 溯源字段")
    void roleSync_returnsRoleFields() throws Exception {
        String body = "{\"message\":\"我的设备无法开机怎么办\",\"scene\":\"customer_service\"}";
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").exists())
                .andExpect(jsonPath("$.data.scene").value("customer_service"))
                .andExpect(jsonPath("$.data.templateId").exists())
                .andExpect(jsonPath("$.data.templateVersion").isNumber())
                .andExpect(jsonPath("$.data.content").exists());
    }

    @Test
    @DisplayName("T02 空白 message 返回业务码 4001")
    void blankMessage_returns4001() throws Exception {
        String body = "{\"message\":\"   \"}";
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    @DisplayName("T02 超长 message 返回业务码 4001")
    void overLengthMessage_returns4001() throws Exception {
        String tooLong = "x".repeat(2001);
        String body = "{\"message\":\"" + tooLong + "\"}";
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    @DisplayName("T02 非法开发归属请求头返回业务码 4001")
    void invalidOwner_returns4001() throws Exception {
        String body = "{\"message\":\"你好\"}";
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Dev-User", "非法用户!")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
