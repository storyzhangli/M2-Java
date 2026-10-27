package com.zl.d14;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d14.config.AiConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认 mock 模式下的 Web 层测试：覆盖正常调用、确定性、空白/缺字段/非法报文校验与响应契约。
 * 全程不依赖外网、不要求真实 API Key。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockChatControllerTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mockChatShouldReturnDeterministicMockReplyWithStableContract() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("message", "设备运行中出现异常振动，应当如何排查？"));

        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // 统一返回体契约
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data").exists())
                // 模拟结果标识契约
                .andExpect(jsonPath("$.data.mode").value("mock"))
                .andExpect(jsonPath("$.data.model").value("offline-mock"))
                .andExpect(jsonPath("$.data.mocked").value(true))
                .andExpect(jsonPath("$.data.content").value(containsString(AiConstants.MOCK_REPLY_MARKER)));
    }

    @Test
    void sameInputShouldProduceSameOutput() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "重复输入一致性测试"));

        MvcResult first = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        assertThatContentEqual(first, second);
    }

    @Test
    void blankMessageShouldBeRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "   "));
        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void emptyMessageShouldBeRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", ""));
        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void missingMessageFieldShouldBeRejected() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void malformedJsonShouldBeRejected() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-a-valid-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    private void assertThatContentEqual(MvcResult first, MvcResult second) throws Exception {
        String c1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("content").asText();
        String c2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").path("content").asText();
        org.assertj.core.api.Assertions.assertThat(c1).isEqualTo(c2);
    }
}
