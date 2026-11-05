package com.zl.d23.adapter.deepseek;

import com.zl.d23.config.AiProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DeepSeek 模型客户端的手动装配（D23 替代 DeepSeek 自动配置）。
 *
 * <p>从应用自有命名空间 {@code app.ai.*}（model/base-url/api-key）构造底层
 * {@link DeepSeekApi} 与 {@link DeepSeekChatModel}，不再读取 {@code spring.ai.deepseek.*}。
 * 同步与反应式客户端复用容器注入的 {@link RestClient.Builder}/{@link WebClient.Builder}，
 * 它们已携带 {@code ModelClientConfiguration} 注入的 JDK HttpClient 超时。</p>
 *
 * <p>重试策略：显式构造总尝试 1 次的 {@link RetryTemplate}，关闭 Spring AI 内置重试，
 * 重试统一由应用层 {@code app.ai.resilience} 治理，避免多层重试叠加。仅在
 * {@code app.ai.mode=deepseek} 时装配；mock 模式不创建任何 ChatModel。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "deepseek")
public class DeepSeekChatModelConfiguration {

    /** 保留 D21 基线的采样温度（原 spring.ai.deepseek.chat.options.temperature=0.7）。 */
    private static final double BASELINE_TEMPERATURE = 0.7d;

    /**
     * 构造供应商中立的 {@link ChatModel} Bean（运行时为 DeepSeekChatModel）。
     */
    @Bean
    public ChatModel chatModel(AiProperties aiProperties,
                               RestClient.Builder restClientBuilder,
                               WebClient.Builder webClientBuilder,
                               ObservationRegistry observationRegistry) {

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .apiKey(aiProperties.getApiKey())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
                .model(aiProperties.getModel())
                .temperature(BASELINE_TEMPERATURE)
                .build();

        // 总尝试 1 次即不重试，关闭内置重试，重试由应用层 resilience 单一责任层治理。
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(defaultOptions)
                .retryTemplate(noRetry)
                .observationRegistry(observationRegistry)
                .build();
    }
}
