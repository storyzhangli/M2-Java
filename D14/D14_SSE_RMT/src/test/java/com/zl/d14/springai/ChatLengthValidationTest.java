package com.zl.d14;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可配置长度上限验证：将 app.ai.max-message-length 调小为 5，
 * 验证业务可调整限制生效（区别于注解上的 2000 硬天花板）。
 */
@SpringBootTest(properties = "app.ai.max-message-length=5")
@AutoConfigureMockMvc
class ChatLengthValidationTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messageWithinConfiguredLimitShouldPass() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "abc"));
        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void messageExceedingConfiguredLimitShouldBeRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "abcdefghij"));
        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("上限")));
    }
}
