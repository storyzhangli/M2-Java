# Spring AI M2 知识笔记生成任务书 Prompt
## 模型调用超时、重试退避、限流与降级；Spring AI Retry 与 Resilience4j 轻量方案比较

> **任务类型：** 技术知识笔记生成，不是项目迭代任务。  
> **目标：** 生成一份准确、精简、可运行、可验证的 Markdown 知识笔记。  
> **技术基线：** JDK 21、Spring Boot 3.5.16、Spring AI 1.1.x；模型服务以 DeepSeek 为主要示例。具体版本和 API 必须先核验，不得凭记忆编造。

---

## 一、执行目标与边界

请围绕模型调用可靠性治理，讲清楚并实践以下内容：

1. 模型调用超时：连接超时、响应/读取超时、单次尝试超时、端到端总时限。
2. 重试与退避：可重试异常、不可重试异常、最大尝试次数、指数退避、最大间隔、随机抖动、总耗时预算。
3. 限流：应用侧主动限流与模型服务端 429 限流的区别；并发限制、排队/拒绝、Retry-After 的处理。
4. 降级：模型不可用、超时、限流或熔断时的安全替代行为，避免伪造模型结果。
5. 方案比较：Spring AI 内置 `spring.ai.retry.*` 与 Resilience4j 轻量组合方案的职责、适用场景、代价及选择建议。
6. 最小实践：使用 Mock 模型/可控故障模拟成功、超时、429、5xx、连续失败、熔断和降级；默认不调用真实模型 API。

不得将本任务扩展为完整微服务容错平台；不为展示技术而无必要引入 MQ、分布式限流、服务网格或复杂多级熔断体系。

## 二、必须覆盖的知识点

### 1. 超时模型与边界

- 连接建立超时、连接池获取超时、响应/读取超时、请求总时限分别控制什么。
- 单次模型调用超时与包含重试、退避在内的端到端 Deadline 的区别。
- HTTP 客户端超时、Spring AI 调用链、Reactor `timeout`、Resilience4j `TimeLimiter` 的作用范围和差异。
- 同步阻塞调用与 `CompletableFuture`、`Mono`/`Flux` 的超时处理差异。
- 客户端超时不等于服务端停止执行；请求结果可能未知，尤其不能据此推断服务端未计费或未处理。
- 流式调用已开始输出后发生超时/断连的处理边界；不能默认普通重试可以无缝续接已输出内容。
- 超时值应结合模型响应时延、业务 SLA、连接池、重试次数和总预算确定；不得随意给出“通用最佳值”。

### 2. Spring AI 内置 Retry

必须针对实际使用的 Spring AI 1.1.x 补丁版本、DeepSeek 模型实现核对：

- `spring.ai.retry.max-attempts`
- `spring.ai.retry.backoff.initial-interval`
- `spring.ai.retry.backoff.multiplier`
- `spring.ai.retry.backoff.max-interval`
- `spring.ai.retry.on-client-errors`
- `spring.ai.retry.exclude-on-http-codes`
- `spring.ai.retry.on-http-codes`

要求说明：

- 每个属性的真实含义、单位、默认值、边界条件、配置绑定位置，以及当前版本/模型实现是否支持。
- `max-attempts` 表示总尝试次数还是额外重试次数，必须依据当前版本实现确认，并在笔记中明确表述。
- 哪些 HTTP 状态码/异常会触发重试，哪些应排除；不得笼统地“所有异常都重试”。
- Spring AI 内置 Retry 的职责边界：它不是连接超时、读取超时、并发限流、熔断和完整降级方案。
- 同步 `call()` 与流式 `stream()` 的重试适用性必须按 DeepSeek/Spring AI 当前实现核验；如果流式重试不支持或存在语义限制，必须明确指出。
- 给出最小 YAML 示例和对应生效验证方式；配置键不得凭其他版本文档推断。

### 3. 重试、退避与请求放大

- 固定间隔、指数退避、指数退避加随机抖动的区别与适用场景。
- `initial interval`、`multiplier`、`max interval`、`max attempts` 的关系；展示一组明确标注为“示例参数”的等待时间计算。
- 随机抖动用于避免大量客户端同时重试造成惊群/重试风暴。
- 识别 400/401/403 等通常不应重试的请求或鉴权错误；429、部分 5xx、临时网络故障在满足条件时有限重试；具体策略按服务端语义和错误响应核验。
- 读取并遵守服务端 `Retry-After`（如提供且当前客户端可获得），避免固定策略覆盖服务端建议。
- 重试次数、每次超时和退避时间必须共同受总 Deadline 限制。
- 多层重试叠加风险：Spring AI Retry、HTTP 客户端、Resilience4j Retry、业务层重试可能造成乘法放大。必须给出“每一层由谁负责重试”的推荐边界。
- 对有副作用的工具调用/工单创建等操作，说明幂等键、去重和结果未知时的保护；不能只因模型调用超时就盲目重放业务动作。

### 4. 限流与并发保护

- 服务端限流（如 HTTP 429）与本地/应用侧 RateLimiter 的区别。
- 按用户、租户、API Key、模型、业务场景或全局维度限流的取舍；本任务只需给出轻量设计，不要求实现分布式限流。
- QPS/请求速率与最大并发数的区别；RateLimiter 与 Bulkhead 的职责差异。
- 超限时立即拒绝、短暂等待或排队的选择；排队必须考虑队列上限、等待超时和请求过期。
- 429 的有限退避、用户友好错误映射、配额保护与成本控制。
- Resilience4j RateLimiter 的关键配置和 API 必须按所选版本核验，例如 `limitForPeriod`、`limitRefreshPeriod`、`timeoutDuration`；解释其语义，不机械照抄默认值。

### 5. 熔断与降级

- Circuit Breaker 的 CLOSED、OPEN、HALF_OPEN 状态及状态转换条件。
- 熔断与限流、重试、超时的差异；熔断用于故障期间快速失败，不是重试的替代名称。
- fallback 必须诚实、可识别、受业务约束：可返回明确的暂不可用提示、缓存的已验证内容、转人工或引导稍后重试。
- 不得将空字符串、臆造答案、旧缓存冒充实时模型结果；缓存降级必须标记来源/时间及适用范围。
- 说明降级是否适用于同步调用、异步调用和流式调用，并核验相应 API。
- 说明恢复探测、半开状态、监控指标与人工干预边界。

### 6. Spring AI Retry 与 Resilience4j 比较

必须提供对比表，至少比较：

| 比较维度 | Spring AI Retry | Resilience4j |
|---|---|---|
| 核心定位 | 模型调用层重试 | 通用容错策略组合 |
| 配置方式 | `spring.ai.retry.*` 等当前版本配置 | Spring Boot 配置、注解或编程式装饰器 |
| 重试分类 | 模型实现/HTTP 状态码与异常策略 | `retryExceptions`、`ignoreExceptions`、自定义谓词等（按版本核验） |
| 退避 | 当前版本支持的 BackOff 配置 | 固定/指数/随机或自定义间隔（按版本核验） |
| 超时 | 不等同于底层网络超时 | TimeLimiter 主要面向受支持的异步执行模型；不能误称其自动终止任意阻塞调用 |
| 限流/并发 | 不提供完整通用策略 | RateLimiter、Bulkhead |
| 熔断/降级 | 不是其完整职责 | CircuitBreaker 与 fallback/恢复组合 |
| 流式支持 | 必须核实提供方和版本限制 | 必须核实 Reactor/异步适配及取消语义 |
| 引入成本 | 通常较低 | 增加依赖、配置、监控和策略组合复杂度 |

必须给出明确的轻量选型结论：

- 什么时候只使用 Spring AI Retry 即可；
- 什么时候保留 Spring AI Retry 并补充 Resilience4j 的限流/熔断/并发隔离；
- 什么时候应关闭或收敛其中一层重试，避免重复重试；
- 对当前学习阶段，优先给出最小可验证方案，不默认引入所有 Resilience4j 模块。

### 7. 监控与诊断

至少说明并给出建议采集项：请求总数、成功/失败数、错误分类、超时数、429 数、重试次数、重试耗时、熔断状态、限流拒绝数、降级次数、端到端延迟及模型/场景维度。

日志只记录必要元数据（请求关联 ID、模型标识、耗时、错误类型、尝试次数）；不得记录 API Key、Authorization Header、完整敏感 Prompt、用户隐私或不必要的模型输入输出。

## 三、最小实践要求

使用一个独立的模型调用服务/适配层演示可靠性策略。先检查已有工程依赖和版本；若只是知识笔记练习，可使用最小独立示例，不要扫描或复制全仓库。

至少实现/展示：

1. Mock 模型客户端或可控 Stub，可按测试用例返回成功、429、503、超时和持续失败。
2. 一套受控的超时配置，明确连接/响应超时与总时限的边界。
3. Spring AI Retry 配置示例，并测试可重试与不可重试错误。
4. 一个最小 Resilience4j 方案：优先从 RateLimiter、CircuitBreaker 或 Bulkhead 中按目标选必要组件；如使用 TimeLimiter，必须说明执行类型和取消限制。
5. 一个安全 fallback，明确提示用户当前使用的是降级响应。
6. 对比测试：仅 Spring AI Retry 与加入必要 Resilience4j 策略后的行为差异。

必须覆盖以下测试：

- 正常成功：一次调用成功，不产生多余重试。
- 鉴权/参数类 4xx：不进行无意义重试。
- 429：有限退避，考虑 `Retry-After`，达到上限后返回受控错误。
- 503/临时网络异常：按白名单有限重试。
- 超时：验证单次超时、总 Deadline 和最终错误映射。
- 持续失败：达到最大尝试次数后停止，不进入无限循环。
- 限流：超过本地配额时按策略拒绝或有限等待。
- 熔断：达到阈值后快速失败，并验证半开恢复路径。
- 降级：使用明确标记的降级响应，不伪装成模型正常输出。
- 多层重试：验证没有发生重复/乘法放大。

测试必须实际执行并报告真实结果；不得用伪代码、静态推演或“预计通过”替代测试证据。若当前环境不能执行，必须明确说明限制和未执行项。

## 四、生成内容的约束

1. 先核验 Spring AI 1.1.x 的准确补丁版本、DeepSeek 模型依赖、Spring Boot 版本和 Resilience4j 兼容版本，再编写示例。
2. 优先使用 Spring AI 1.1.x 与 Spring Boot 3.5.16 对应的官方文档、API JavaDoc、官方源码及 Resilience4j 官方文档。若当前 1.1.x 小版本不一致，必须注明差异。
3. 不得将 Spring AI 2.x、1.0.x 或旧配置键直接套用于 1.1.x；不得混用不同版本的属性名、包名、构造器或 starter。
4. 对超时配置必须指出究竟属于底层 HTTP 客户端、Spring AI、Reactor 还是 Resilience4j；不允许将 `spring.ai.retry.*` 描述成完整超时配置。
5. 对默认值、重试次数语义、流式 API、取消行为和配置绑定，必须有当前版本依据；无法确认则标注“需在实际版本验证”，不得猜测。
6. 所有代码应能对应到明确依赖和版本；避免只给孤立片段而缺少必要 import、配置或测试说明。
7. 解释优先于罗列属性：先讲问题和职责边界，再给配置与代码。
8. 不扩展无关主题；不重复上一份“鉴权、限流、网络错误、超时和重试退避”通用错误分类笔记，只保留理解本主题所需的简短回顾。
9. 不伪造性能数据、成功率、压测结果、测试结果或官方结论。

## 五、最终 Markdown 笔记结构

请直接生成最终知识笔记，建议文件名：

`Spring-AI-M2-模型调用超时重试退避限流与降级.md`

内容按以下结构组织：

1. 学习目标与核心结论（简短）
2. 模型调用可靠性全景图（Mermaid）
3. 超时类型与调用总时限
4. Spring AI 1.1.x Retry 属性逐项说明与版本核验表
5. 重试决策矩阵与退避时间示例
6. 限流、并发隔离、熔断、降级的职责边界
7. Spring AI Retry vs Resilience4j 对比与轻量选型建议
8. 最小可运行实践（依赖、配置、Java 代码、Mock、运行命令）
9. 测试用例与实际执行结果/证据
10. 常见故障与排查步骤
11. 关键知识总结与面试表达
12. 官方参考资料

至少提供：

- 一张“异常/状态 → 是否重试 → 退避 → 最终处理”的决策表；
- 一张“Spring AI Retry 与 Resilience4j 职责分层”图；
- 一张最小配置示例表，所有键和默认值均注明适用版本；
- 一套能够用 Mock 复现故障的最小代码和测试。

## 六、引用规范与验收

技术引用统一采用：

`English original title | 中文翻译标题：[完整 URL](完整 URL)`

引用紧跟其支持的技术结论。优先官方资料，明确标注文档版本；不得引用搜索摘要代替原始文档，不得编造链接。

建议优先核验以下官方资料，并根据实际 Spring AI 补丁版本替换为精确版本链接：

- DeepSeek Chat | DeepSeek 对话模型（Spring AI）：https://docs.spring.io/spring-ai/reference/1.1/api/chat/deepseek-chat.html
- Retry | 重试机制（Spring AI DeepSeek Chat）：https://docs.spring.io/spring-ai/reference/1.1/api/chat/deepseek-chat.html
- Introduction | Resilience4j 介绍：https://resilience4j.readme.io/docs/getting-started
- Getting Started | Resilience4j Spring Boot 入门：https://resilience4j.readme.io/docs/getting-started-3
- RateLimiter | 限流器：https://resilience4j.readme.io/docs/ratelimiter
- Retry | 重试：https://resilience4j.readme.io/docs/retry
- CircuitBreaker | 熔断器：https://resilience4j.readme.io/docs/circuitbreaker
- TimeLimiter | 时间限制器：https://resilience4j.readme.io/docs/timeout
- Bulkhead | 舱壁隔离：https://resilience4j.readme.io/docs/bulkhead

如某个版本化 URL 不可访问，不得假装已读取；应寻找官方可访问的对应版本页面，并注明版本来源。

最终验收标准：

- [ ] 清楚区分连接超时、响应超时、单次尝试超时和端到端 Deadline。
- [ ] 能准确解释 Spring AI 1.1.x Retry 属性、默认值及模型/流式适用边界。
- [ ] 能根据异常类型选择重试、不重试、退避、限流或降级。
- [ ] 能解释 Spring AI Retry 与 Resilience4j 的职责差异及重复重试风险。
- [ ] 能用 Mock 测试成功、429、5xx、超时、限流、熔断和降级。
- [ ] 不泄露 API Key，默认不调用真实模型。
- [ ] 测试结果真实可追溯，未执行项如实说明。
- [ ] Markdown 内容精简、代码版本匹配、引用可访问且准确。

**执行要求：** 不要再次生成任务书，不要先输出冗长分析。完成版本核验后，直接生成上述 Markdown 知识笔记；若某项无法核验或执行，在文档中明确标注原因及影响。
