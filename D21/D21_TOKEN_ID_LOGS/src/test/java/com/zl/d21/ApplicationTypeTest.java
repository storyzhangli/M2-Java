package com.zl.d21;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用类型运行时核验测试（T08）。
 *
 * <p>在运行时确认应用为 Servlet/Spring MVC（Tomcat），而非反应式 WebFlux/Netty 服务器；
 * 同时确认 reactor-core 作为模型层库存在，澄清「有 webflux 类库 ≠ 应用切到 WebFlux」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTypeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("T08 运行时上下文是 Servlet Web 容器（Tomcat）")
    void context_isServletTomcat() {
        assertThat(context).isInstanceOf(WebApplicationContext.class);
        assertThat(context).isInstanceOf(AnnotationConfigServletWebServerApplicationContext.class);

        assertThat(context.getBean(ServletWebServerFactory.class))
                .isInstanceOf(TomcatServletWebServerFactory.class);
        // 不存在反应式 Web 服务器工厂，证明没有 Netty/WebFlux 服务器
        assertThat(context.getBeanNamesForType(ReactiveWebServerFactory.class)).isEmpty();
    }

    @Test
    @DisplayName("T08 类路径无 Netty 服务器但有反应式核心库")
    void classpath_noNettyServerButHasReactor() {
        assertThat(isClassPresent("reactor.netty.http.server.HttpServer")).isFalse();
        assertThat(isClassPresent("reactor.core.publisher.Flux")).isTrue();
        assertThat(isClassPresent("org.springframework.web.reactive.function.client.WebClient")).isTrue();
    }

    private boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
