package com.zl.d15;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * D01 Spring AI 初始化模块启动类。
 *
 * <p>默认以 mock 模式启动，无需真实 API Key、无需外网即可完成上下文装配与接口验证；
 * 切换为 deepseek 真实模式仅需通过环境变量注入 {@code AI_MODE=deepseek} 与真实
 * {@code DEEPSEEK_API_KEY}，无需修改或重新提交源代码。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class D15SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(D15SpringAiApplication.class, args);
        System.out.println("====== hello Spring AI (日常线) ======");
    }
}
