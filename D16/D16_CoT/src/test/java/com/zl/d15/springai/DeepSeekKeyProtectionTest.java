package com.zl.d15.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d16.common.AiConfigurationException;
import com.zl.d16.config.AiConstants;
import com.zl.d16.service.DeepSeekAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模式（app.ai.mode=deepseek）下的密钥保护测试。
 *
 * <p>使用 {@link MockitoBean} 把自动配置的 DeepSeekChatModel 替换为 Mockito 模拟对象，
 * 在<b>完全不联网、不产生费用</b>的前提下验证：缺失/空白/占位密钥会在发起调用前被拦截，
 * 且底层 ChatModel 零交互。</p>
 */
@SpringBootTest(properties = "app.ai.mode=deepseek")
@AutoConfigureMockMvc
class DeepSeekKeyProtectionTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void placeholderKeyShouldFailFastAndNeverCallRemoteModel() throws Exception {
        // 未设置 DEEPSEEK_API_KEY 时，spring.ai.deepseek.api-key 解析为占位串
        String body = objectMapper.writeValueAsString(Map.of("message", "真实模式占位密钥保护测试"));

        mockMvc.perform(post(CHAT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001))
                // 对外响应不得回显占位密钥或堆栈
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(AiConstants.PLACEHOLDER_API_KEY))));

        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verifyNoInteractions(chatModel);
    }

    @Test
    void blankNullAndPlaceholderKeysShouldBeRejectedAtServiceLevel() {
        ChatModel isolatedModel = mock(ChatModel.class);
        String[] invalidKeys = {null, "", "   ", AiConstants.PLACEHOLDER_API_KEY, "some-mock-value"};

        for (String invalidKey : invalidKeys) {
            // D16 构造器新增 reasonerModel 参数（思考模式模型切换），密钥校验逻辑与该参数无关
            DeepSeekAiChatService service =
                    new DeepSeekAiChatService(isolatedModel, invalidKey,
                            "deepseek-flash", "deepseek-reasoner");
            assertThatThrownBy(() -> service.chat("hi"))
                    .isInstanceOf(AiConfigurationException.class)
                    .hasMessageContaining("密钥");
        }

        // 任何无效密钥场景下，底层模型都不应被触碰
        verifyNoInteractions(isolatedModel);
    }
}
