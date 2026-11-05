package com.zl.d23;

import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * D23 启动类。
 *
 * <p>显式排除 {@link DeepSeekChatAutoConfiguration}：模型客户端改由适配层
 * {@code DeepSeekChatModelConfiguration} 依据应用自有命名空间 {@code app.ai.*} 手动构造，
 * 从而消除 {@code spring.ai.deepseek.*} 与 {@code app.ai.*} 两套配置并存的隐患。</p>
 *
 * <p>默认以 mock 模式启动，无需真实 API Key、无需外网即可完成上下文装配与接口验证；
 * 切换为真实模式仅需通过环境变量注入 {@code AI_MODE=deepseek} 与真实 {@code AI_API_KEY}，
 * 无需修改或重新提交源代码。</p>
 */
@SpringBootApplication(exclude = DeepSeekChatAutoConfiguration.class)
@ConfigurationPropertiesScan
public class D23SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(D23SpringAiApplication.class, args);
        System.out.println("====== D23 hello Spring AI (日常线) ======");
    }
}
