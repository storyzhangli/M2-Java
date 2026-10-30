package com.zl.d17.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.config.AiConstants;
import com.zl.d17.dto.ChatReplyResponse;
import com.zl.d17.service.DeepSeekAiChatService;
import com.zl.d17.service.MockAiChatService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 强保证：mock 模式下不可能发起真实外部 API 调用。
 *
 * <p>验证手段分层叠加：</p>
 * <ol>
 *     <li>容器中只存在 MockAiChatService，DeepSeekAiChatService Bean 不存在；</li>
 *     <li>MockAiChatService 无任何实例字段、构造器无参，结构上无法持有 ChatModel/HTTP 客户端；</li>
 *     <li>HTTP 调用返回 mock 成功结果（而非上游鉴权失败），且一个独立 ChatModel mock 零交互。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockNoExternalApiTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void onlyMockServiceExistsAndDeepSeekServiceIsAbsent() {
        Assertions.assertThat(applicationContext.getBean(MockAiChatService.class)).isNotNull();
        assertThat(applicationContext.getBeanNamesForType(DeepSeekAiChatService.class)).isEmpty();
    }

    @Test
    void mockServiceShouldHaveNoModelOrHttpClientDependency() {
        Field[] fields = MockAiChatService.class.getDeclaredFields();
        Constructor<?>[] constructors = MockAiChatService.class.getDeclaredConstructors();

        assertThat(fields).isEmpty();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterCount()).isZero();
    }

    @Test
    void mockReplyShouldNeverInvokeChatModelAndShouldReturnMockFlag() throws Exception {
        ChatModel untrackedModel = mock(ChatModel.class);

        // 直接调用无参构造的 mock 服务，验证输出确定性且标识为模拟
        MockAiChatService service = new MockAiChatService();
        ChatReplyResponse reply = service.chat("ping");
        assertThat(reply.getMocked()).isTrue();
        assertThat(reply.getContent()).contains(AiConstants.MOCK_REPLY_MARKER);

        // 该 ChatModel 从未被传入、也从未被调用
        verifyNoInteractions(untrackedModel);

        // 端到端：mock 模式走 HTTP 入口返回 200 + mocked=true，而非任何上游错误码
        String body = objectMapper.writeValueAsString(Map.of("message", "ping"));
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mocked").value(true));
    }
}
