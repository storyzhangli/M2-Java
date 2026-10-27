package com.zl.d14;

import com.zl.d14.config.AiConstants;
import com.zl.d14.service.AiChatService;
import com.zl.d14.service.DeepSeekAiChatService;
import com.zl.d14.service.MockAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收点：Spring 应用上下文可在默认 mock 模式、无真实密钥、无外网条件下正常启动，
 * 且 DeepSeek 自动装配 Bean 与外部化配置绑定正确。
 */
@SpringBootTest
class ApplicationContextLoadTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private Environment environment;

    @Test
    void contextShouldLoad() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void deepSeekChatModelShouldBeAssembledEvenInMockMode() {
        // 自动配置期不发起网络请求，占位密钥下 Bean 仍应成功装配
        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(DeepSeekChatModel.class);
    }

    @Test
    void mockServiceShouldBeActiveAndDeepSeekServiceAbsentByDefault() {
        assertThat(aiChatService).isInstanceOf(MockAiChatService.class);
        // 真实模式实现不应出现在 mock 上下文里，从 Bean 装配层面阻断外网调用路径
        assertThat(applicationContext.getBeanNamesForType(DeepSeekAiChatService.class)).isEmpty();
    }

    @Test
    void externalizedPropertiesShouldBindToExpectedDefaults() {
        assertThat(environment.getProperty("app.ai.mode")).isEqualTo(AiConstants.MODE_MOCK);
        assertThat(environment.getProperty("spring.ai.deepseek.chat.options.model"))
                .isEqualTo("deepseek-flash");
        assertThat(environment.getProperty("spring.ai.deepseek.base-url"))
                .isEqualTo("https://api.deepseek.com");
        // 解析后的密钥必须是显式无效占位串，而非真实凭据
        assertThat(environment.getProperty("spring.ai.deepseek.api-key"))
                .isEqualTo(AiConstants.PLACEHOLDER_API_KEY);
    }
}
