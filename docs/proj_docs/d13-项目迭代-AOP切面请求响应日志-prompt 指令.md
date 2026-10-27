# 项目迭代任务书：AOP 请求与响应日志

## 1. 目标与约束

在现有 Java 项目中增量实现统一的 HTTP 请求/响应日志。每次纳入范围的请求都必须记录请求参数与响应参数/返回对象，并通过同一个 `requestId/traceId` 关联；同时记录耗时、状态及异常。

默认技术基线：JDK 21、Spring Boot 3.5.x（项目基线 3.5.16）、Maven 3.9.x。**实际依赖和实现以仓库现状为准**。沿用现有日志、TraceId、统一响应、异常处理和命名风格；不直接假设项目结构，不大范围重构，不引入无必要依赖。

## 2. 先检查真实仓库，不要直接编码

先检查并简要报告：

- 模块结构、`pom.xml`、Spring Boot / Spring Framework 版本、Spring MVC 或 WebFlux。
- Controller 范围、统一响应类型、异常处理器、现有 AOP、Filter/Interceptor、TraceId/MDC 和日志配置。
- 现有访问日志/请求日志、测试结构、配置模式及文档目录规范。

指出已有能力、缺口、改动文件预期和待核实项。没有实际检查或执行的内容必须标注“待核实”，不得声称已编译或测试。

## 3. 日志字段：请求和响应都必须有参数

每次请求的日志至少应包括：`requestId/traceId`、事件类型、时间、HTTP Method、URI、Query 参数、可获得的 Path 参数、必要请求头、Controller/业务方法、方法入参（绑定后的 DTO/`@RequestBody`、`@RequestParam` 等）、客户端信息（遵循项目规范）。

每次响应的日志至少应包括：同一请求标识、HTTP 状态码、耗时、方法返回对象/统一响应数据、可安全获取的响应参数或正文摘要；发生异常时记录异常类型、必要错误信息及处理状态。

“参数完整”不等于无条件记录机密：`Authorization`、Cookie、密码、API Key、Token、密钥、身份证件号、银行卡号及项目识别出的敏感字段必须脱敏或排除。日志字段、排除规则与脱敏策略应可配置并有测试。

大请求/响应须设置缓存和日志长度上限，超限截断并标明 `truncated=true`；文件、二进制、上传/下载、SSE/流式响应及无法安全重放的内容，不得强行完整缓存或打印正文，记录元数据、大小和不记录原因即可。

## 4. 技术方案必须正确处理 AOP 与 HTTP 边界

- 使用 Spring AOP `@Around` 或最合适的 Advice 记录 Controller 方法参数、返回对象、执行耗时和异常；拦截范围应明确，避免重复记录。
- **不能假设 AOP 单独就能覆盖整个 HTTP 生命周期或获得最终序列化后的响应正文**。如需记录最终 HTTP 状态、异常处理器生成的响应或原始响应体，应根据项目 Web 栈评估配合 Filter/Interceptor 与合适的 request/response wrapper。
- 必须识别 Spring AOP 代理限制，包括类内部 `this.method()` 自调用通常绕过代理的 Advice。
- 如使用 `ContentCachingRequestWrapper`，核查其只缓存已读取内容的行为与缓存大小；如使用 `ContentCachingResponseWrapper`，确保缓存响应被正确写回客户端。不得重复消费请求流，也不得破坏原有响应。
- 以仓库实际栈选择 MVC 或 WebFlux 实现，不可照搬不兼容的 Servlet 方案。
- 明确路径/包的拦截与排除范围，评估健康检查、静态资源、Swagger/Knife4j、Actuator、内部回调、文件和流式端点，避免重复日志或误记敏感数据。

## 5. 日志设计与运行质量

- 同一请求的 request/response/error 事件必须可以用同一 ID 检索；优先复用现有 TraceId/MDC，不存在时再设计生成、传递、响应返回和清理流程。
- 优先遵守项目现有日志格式；若无约定，采用易检索的结构化字段。
- 序列化应处理循环引用、不可序列化对象、超大集合与异常 `toString()`；日志失败不能改变业务返回或遮蔽原始异常。
- 不无限缓存正文；不能只为了记录响应而改变响应类型、状态码、Header 或客户端可见内容。
- 日志级别合理，避免无控制打印完整堆栈或同一 Body 多次。
- 如果已有异步日志、结构化日志或采样能力，优先评估复用。

## 6. 按阶段实施

**阶段 A：现状审查**：提交技术栈、已有日志/TraceId/异常处理、功能缺口与待核实项。

**阶段 B：详细设计**：提交拦截范围、AOP 与 Filter/Interceptor 分工、日志字段、TraceId 方案、脱敏和截断规则、配置项、依赖改动、风险及测试方案。若原始需求在当前技术栈下不可直接实现，先说明限制和替代方案。
**详细设计文档存放路径：仓库根路径 ./docs/proj_docs**

**阶段 C：增量编码**：仅修改必要文件；沿用现有架构和风格；说明每个变更的目的。不输出无关的大段代码，不做未授权的大范围重构。

**阶段 D：测试验证**：至少验证：

1. 正常请求的请求参数、响应参数、状态、耗时和 requestId 均可查到，且请求与响应可关联。
2. Query/Path 参数、JSON DTO、统一响应及异常响应路径。
3. 密码、Token、Authorization、Cookie 和自定义敏感字段已脱敏 (**如果没有这些敏感字段则可以忽略,继续下一步骤**)。
4. 超长正文截断；文件、二进制和流式响应未被破坏。
5. 404、参数绑定失败及未进入 Controller 的请求是否被覆盖，并如实说明方案边界。
6. 日志异常不影响业务，TraceId 不串线，响应没有被消费或写回错误。
7. 编译、现有测试和新增测试。

真实执行适用的 Maven 编译、单元测试和集成测试。未执行的测试须写明原因，不得虚报结果。

**阶段 E：交付**：提供实施摘要、文件清单、设计取舍、脱敏日志示例、测试命令与真实结果、未解决风险及 Git diff 摘要。只有实际创建提交才报告 commit hash。
交付报告存放路径：仓库根路径 ./docs/proj_docs

## 7. 官方资料核验

核对与仓库实际 Spring Framework 版本相匹配的资料：

- Proxying Mechanisms | 代理机制：[https://docs.spring.io/spring-framework/reference/6.2/core/aop/proxying.html](https://docs.spring.io/spring-framework/reference/6.2/core/aop/proxying.html)
- AOP Concepts | AOP 概念：[https://docs.spring.io/spring-framework/reference/6.2/core/aop/introduction-defn.html](https://docs.spring.io/spring-framework/reference/6.2/core/aop/introduction-defn.html)
- ContentCachingRequestWrapper Javadoc | 请求内容缓存包装器 API 文档：[https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/web/util/ContentCachingRequestWrapper.html](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/web/util/ContentCachingRequestWrapper.html)
- ContentCachingResponseWrapper Javadoc | 响应内容缓存包装器 API 文档：[https://docs.spring.io/spring-framework/docs/6.2.8/javadoc-api/org/springframework/web/util/ContentCachingResponseWrapper.html](https://docs.spring.io/spring-framework/docs/6.2.8/javadoc-api/org/springframework/web/util/ContentCachingResponseWrapper.html)
- Logging | Spring Boot 日志：[https://docs.spring.io/spring-boot/reference/features/logging.html](https://docs.spring.io/spring-boot/reference/features/logging.html)

引用使用“英文原始标题 | 中文翻译标题：[完整 URL](完整 URL)”格式，且必须按仓库实际依赖版本确认适用性。

## 8. 完成标准

只有满足以下条件才算完成：目标范围内每次请求均有可关联的请求/响应日志；请求参数和响应参数/返回对象均被记录（敏感字段脱敏、流式/文件正文按边界处理）；日志包含状态、耗时和异常；未破坏现有业务响应；测试有真实证据；未执行项与剩余风险清楚列出；Prompt 文件路径遵循仓库约定。

**现在开始：先检查真实仓库并报告现状与设计，再实施。不能把“记录每次请求与响应参数”简化成只打印 URL/方法名，也不能为了完整记录而泄露凭据或破坏响应。**
