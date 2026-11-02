package com.zl.d20.resilience;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

import java.net.http.HttpClient;

/**
 * 模型 HTTP 客户端配置：把连接/读取超时显式注入 Spring AI DeepSeek 客户端使用的
 * RestClient 与 WebClient 构建器。
 *
 * <p>本应用类路径无 Apache HttpClient5、无 reactor-netty，因此同步与反应式客户端统一基于
 * JDK 内置 {@link HttpClient}：连接超时由 {@link HttpClient#connectTimeout} 控制，
 * 读取超时由 {@code setReadTimeout} 控制。两个 Customizer 由 Spring Boot 自动应用到
 * 容器内唯一的 RestClient/WebClient 构建器，作用范围即模型客户端。</p>
 *
 * <p>该类只负责“客户端工厂与超时”，不承载重试逻辑，符合单一职责。</p>
 */
@Configuration
public class ModelClientConfiguration {

    /**
     * 共享的 JDK HttpClient，统一连接超时。
     */
    @Bean
    public HttpClient modelHttpClient(ModelResilienceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    /**
     * 同步 RestClient：设置 JDK 请求工厂与连接/读取超时。
     */
    @Bean
    public RestClientCustomizer modelRestClientCustomizer(
            HttpClient modelHttpClient, ModelResilienceProperties properties) {
        return restClientBuilder -> {
            JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(modelHttpClient);
            requestFactory.setReadTimeout(properties.getReadTimeout());
            restClientBuilder.requestFactory(requestFactory);
        };
    }

    /**
     * 反应式 WebClient：设置 JDK 连接器与连接/读取超时。
     */
    @Bean
    public WebClientCustomizer modelWebClientCustomizer(
            HttpClient modelHttpClient, ModelResilienceProperties properties) {
        return webClientBuilder -> {
            JdkClientHttpConnector connector =
                    new JdkClientHttpConnector(modelHttpClient);
            connector.setReadTimeout(properties.getReadTimeout());
            webClientBuilder.clientConnector(connector);
        };
    }
}
