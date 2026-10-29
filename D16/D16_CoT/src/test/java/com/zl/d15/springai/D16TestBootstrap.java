package com.zl.d15.springai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * D16 测试引导配置。
 *
 * <p>D16 骨架将主代码包名由 {@code com.zl.d15} 调整为 {@code com.zl.d16}，但测试包仍保留
 * {@code com.zl.d15.springai}。Spring Boot 测试默认从测试类所在包向上查找
 * {@code @SpringBootConfiguration}，无法跨兄弟包发现 {@code com.zl.d16.D16PlusSpringAiApplication}，
 * 直接表现为 {@code Unable to find a @SpringBootConfiguration}。</p>
 *
 * <p>本类仅存在于 test classpath，作为测试上下文的唯一引导入口，显式扫描主代码包
 * {@code com.zl.d16} 并启用同名配置属性扫描，行为与正式启动类保持一致；不引入任何测试专用 Bean，
 * 从而避免改动 20 个既有测试类的包结构与注解。</p>
 */
@SpringBootApplication(scanBasePackages = "com.zl.d16")
@ConfigurationPropertiesScan(basePackages = "com.zl.d16")
class D16TestBootstrap {
}
