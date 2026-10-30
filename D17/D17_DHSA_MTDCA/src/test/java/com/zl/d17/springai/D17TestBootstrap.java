package com.zl.d17.springai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * D17 测试引导配置。
 *
 * <p>测试包为 {@code com.zl.d17.springai}，Spring Boot 测试默认从测试类所在包向上查找
 * {@code @SpringBootConfiguration}，无法跨包发现 {@code com.zl.d17.D17SpringAiApplication}，
 * 直接表现为 {@code Unable to find a @SpringBootConfiguration}。</p>
 *
 * <p>本类仅存在于 test classpath，作为测试上下文的唯一引导入口，显式扫描主代码包
 * {@code com.zl.d17} 并启用配置属性扫描，行为与正式启动类保持一致；不引入任何测试专用 Bean。</p>
 */
@SpringBootApplication(scanBasePackages = "com.zl.d17")
@ConfigurationPropertiesScan(basePackages = "com.zl.d17")
class D17TestBootstrap {
}
