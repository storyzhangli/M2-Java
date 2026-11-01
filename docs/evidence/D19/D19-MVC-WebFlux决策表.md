# D19 MVC / WebFlux 方案决策表

> 决策依据：D19 实际 POM、依赖树、1.1.8 字节码核验与启动/运行时证据（T08 启动后复核）。

## 1. 决策表

| 维度 | 事实 | 证据 |
|---|---|---|
| 当前 Web 依赖 | 仅 `spring-boot-starter-web`（Tomcat + MVC）；无 `spring-boot-starter-webflux` | D19_SCI/pom.xml |
| 反应式运行依赖 | `reactor-core 3.7.19`、`spring-webflux 6.2.19`（库）随 spring-ai-model 传递；无 reactor-netty、无 Netty 服务器 | 依赖树 D19-dependency-tree.txt |
| 实际应用类型 | Servlet（Spring MVC + 嵌入式 Tomcat 10.1.55） | 依赖树；启动日志（T08 复核） |
| Spring AI 流式要求 | 模型层 `stream()` 返回 Reactor `Flux`，需 reactor-core；不要求容器是 WebFlux | javap ChatModel/StreamingChatModel |
| 推荐组合 | **保留 MVC 主栈**：控制器用 `SseEmitter` 承载异步响应，手动订阅 `Flux<StreamChunk>` 桥接 SSE | ChatStreamController 现状 |
| 备选方案 | 整体迁移 WebFlux（Netty 服务器 + 全反应式栈） | 见第 3 节，不推荐 |
| 兼容风险 | Servlet 写出仍含阻塞部分，非端到端完全非阻塞；代理缓冲可能造成伪流式 | 第 4 节 |
| 验证证据 | 真机 SSE 分段、X-Accel-Buffering:no、T08 应用类型断言 | 真机证据 + 测试 |

## 2. 为什么保留 MVC

1. 同步接口稳定且契约不许改，MVC 已满足全部业务需求；没有「必须 WebFlux」的技术触发条件。
2. Spring AI 1.1.8 的流式能力本质是返回 Reactor `Flux`，只要类路径有 reactor-core 即可，**与 Web 容器类型解耦**；当前 reactor-core 已随 spring-ai 传递，无需新增依赖。
3. Spring MVC 从 6.x 起支持异步请求与响应式返回值适配；本项目采用更显式的 `SseEmitter` + 手动订阅，错误、取消、超时可控，且不依赖 `ReactiveTypeHandler` 的隐式适配。
4. 双依赖（web + webflux starter）时 Spring Boot 默认仍按 MVC 装配；本项目连 webflux starter 都没有，应用类型更明确为 Servlet。

## 3. 为什么不整体迁移 WebFlux

- 持久层是 MyBatis（阻塞 JDBC），迁移后需处理阻塞 DAO 与反应式调用的线程边界，成本高、风险大。
- 过滤器、安全、TraceId、AOP、事务与测试均基于 Servlet 体系，迁移会造成大面积无需求改动，违背最小增量。
- 嵌入式服务器会从 Tomcat 切到 Netty，部署与运维口径变化，超出本次「加一个流式接口」的范围。
- 结论：迁移收益（端到端非阻塞）不足以抵消其对同步契约与既有体系的冲击。

## 4. 边界与风险（如实记录）

- **非端到端非阻塞**：`SseEmitter` 在 Servlet 容器内，`emitter.send` 的实际字节写出仍发生在容器线程，存在阻塞写；非阻塞只体现在「模型层 Flux + 容器异步不长期占用请求线程」，不夸大为全链路 reactive。
- **背压（Backpressure）**：模型 Flux 经 Reactor 操作符链传递需求；SSE 写出若慢，靠 `safeSend` 失败/取消上游来收敛，本阶段不实现复杂的逐条请求背压控制器。
- **伪流式**：反向代理默认缓冲会攒包，需 `X-Accel-Buffering:no` 并在网关侧关闭缓冲；本机直连 Tomcat 无此问题。
- **chunk 口径**：每次发送的是模型/服务产出的片段，不保证等于单个字符或 token，前端按到达顺序追加即可。

## 5. 最终结论

- 应用确定运行在 **Spring MVC / Servlet（Tomcat）**，反应式仅作为模型层数据流与异步桥接手段。
- 不新增 POM 依赖、不更换服务器、不迁移主栈；流式主代码骨架已具备，本阶段重心是测试覆盖、真机验证与文档证据。
