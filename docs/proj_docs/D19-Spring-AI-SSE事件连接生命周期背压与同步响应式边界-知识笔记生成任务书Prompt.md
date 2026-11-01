# Spring AI 知识笔记生成任务书 Prompt
## SSE 事件、连接生命周期、背压与同步/响应式边界

> **任务类型：** 知识笔记 + 最小流式响应练习  
> **目标：** 生成一份可学习、可运行、可验证的 Markdown 知识笔记。只完成本主题，不扩展为完整项目迭代。

## 一、任务目标

围绕 Spring AI 流式响应，讲清楚并通过最小代码验证：

1. SSE（Server-Sent Events）的协议格式、事件语义及其与 HTTP 流式响应的关系。
2. SSE 连接从建立、持续推送、心跳、完成、超时、异常到客户端断开/重连的生命周期。
3. Reactor 背压的含义、需求传播、缓冲与取消；以及背压在模型生成、应用处理、HTTP/SSE、浏览器之间的边界。
4. Spring AI `ChatClient.call()` 与 `ChatClient.stream()` 的差别，以及同步/阻塞、异步、响应式不是同义词。
5. Spring MVC 与 Spring WebFlux 的处理模型、线程与 I/O 边界，以及在现有 Spring Boot 项目中选择方案的依据。
6. 完成一个默认不调用真实模型的最小流式响应练习，并给出执行与验收证据。

## 二、学习与版本基线

默认基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.x、Maven 3.9.16。实际项目版本优先以 `pom.xml` 和依赖树为准；不要擅自升级依赖。

执行前核验以下内容：
- Spring AI 当前 1.1.x 对应版本中 `ChatClient.stream()`、`Flux<String>`、`Flux<ChatResponse>` 的实际 API。
- 当前 Spring Boot / Spring Framework / Reactor 依赖版本及 MVC/WebFlux 组合。
- `spring-boot-starter-web`、`spring-boot-starter-webflux` 是否已存在；不要为了示例无理由同时引入或替换 Web 技术栈。
- 若官方文档页面已经切换到 2.x 或其他版本，必须定位 1.1.x 对应文档，不能直接复制新版本 API。

版本不一致时，以实际依赖和匹配版本的官方文档为准，并在笔记开头标注差异。

## 三、必须覆盖的核心知识

### 1. SSE 协议与事件模型

必须解释：
- SSE 是基于 HTTP 的服务器到客户端单向事件流，不是 WebSocket，也不是双向通信协议。
- 响应媒体类型 `text/event-stream`、UTF-8 编码、事件以空行分隔。
- `data`、`event`、`id`、`retry` 字段及注释行/心跳的用途。
- 一个 SSE event 与一次 HTTP write、一次网络数据包、一个模型 token/chunk 不是同一概念；不得假设它们一一对应。
- 浏览器 `EventSource` 的 `CONNECTING`、`OPEN`、`CLOSED` 状态，默认 `message` 事件、命名事件、`close()` 与自动重连的基本行为。
- `Last-Event-ID`、事件 ID、重连与业务幂等之间的关系；不能把自动重连等同于业务消息不丢失或恰好一次处理。
- SSE 与普通分块响应、NDJSON、WebSocket 的适用场景差异。

至少给出一段合法 SSE 原始报文，并逐字段解释。

### 2. 连接生命周期与资源管理

按时间顺序说明：
1. 客户端发起请求及服务端响应头建立。
2. 服务端持续发送数据事件。
3. 心跳/注释事件与代理、负载均衡、网关空闲超时的关系。
4. 正常完成、业务错误、模型调用失败、超时等终止路径。
5. 客户端主动关闭、浏览器页面离开、网络中断导致的断连。
6. 自动重连、事件续传的限制及客户端重复请求风险。

必须说明并展示：超时设置、异常处理、取消传播、资源释放、连接数限制、并发隔离和日志关联 ID。区分“服务端已完成”“客户端已收到全部数据”“业务已持久化”等不同状态。

### 3. Reactor 与背压

必须覆盖：
- `Publisher` / `Subscriber` / `Subscription` 与 `request(n)`、`cancel()` 的基本关系。
- 下游需求如何向上游传播；`Long.MAX_VALUE` 表示无界需求的意义。
- 背压、缓冲、丢弃、限速和取消的区别。
- `onBackpressureBuffer`、`limitRate` 等操作符仅在需要时介绍，说明它们改变的是哪一段需求/缓冲行为，不要堆砌操作符。
- `block()`、`blockFirst()`、`blockLast()`、普通 `subscribe()` 等操作可能引入阻塞或无界请求的风险；指出它们不应被随意放入 WebFlux 请求链。
- 慢客户端、生产速度快于消费速度、缓冲区增长、内存压力和连接堆积的关系。
- 模型供应商是否真正支持取消生成、取消信号能否传到上游，必须结合具体模型适配器核验；不得宣称 Reactor 背压必然能让远端模型立即停止生成。
- SSE/HTTP/浏览器网络层不等同于 Reactor 内部背压协议；说明各层缓冲和取消传播可能存在边界。

至少提供一个小型 Reactor 示例，演示需求、请求数量或取消行为，并解释输出。

### 4. 同步、异步、响应式的边界

用对比表区分：
- 同步调用：调用线程等待结果。
- 异步执行：任务可在之后完成，调用方不必一直阻塞等待；异步本身不等于响应式。
- 响应式：通过 Publisher 描述 0..N 个数据项及其订阅、需求和取消信号；不等于“自动多线程”或“没有阻塞”。
- 流式：数据逐步可用/逐步传输；不自动意味着端到端非阻塞。

必须结合 Spring AI 1.1.x 解释：
- `ChatClient.call()` 与 `ChatClient.stream()` 的 API 语义。
- `stream().content()` 返回 `Flux<String>`；`stream().chatResponse()` 返回 `Flux<ChatResponse>`，按实际版本核验。
- 触发模型调用的终端链路及订阅行为；不要把构造 Flux 与实际消费/执行混为一谈。
- Spring AI 1.1.x 对流式调用的 Reactive stack 要求，以及 imperative 应用使用流式能力时的依赖注意事项。

必须结合 Spring Framework 文档准确说明 MVC 与 WebFlux：
- Spring MVC 可以支持响应式类型作为返回值，也支持 SSE/流式输出；但响应写入仍可能是阻塞式，并由异步执行器承担。
- WebFlux 的核心优势是非阻塞 I/O 与响应式处理链，不代表业务代码、驱动或第三方调用天然非阻塞。
- 不得简单写成“MVC 不支持 Flux/SSE”或“用了 Flux 就是 WebFlux”。
- 选择应结合现有项目技术栈、依赖、阻塞组件、团队熟悉度和负载模式，不得无理由把 MVC 项目整体改造成 WebFlux。

## 四、最小实践练习

以现有项目技术栈为准，先检查相关 Web 依赖和接口组织方式。默认完成一个**模拟数据流式接口**，不调用 DeepSeek 或其他真实模型：

1. 提供一个最小 HTTP 流式接口，返回 `text/event-stream`。
2. 用 `Flux` 模拟分段文本，逐段发送，并在结束时发送明确的结束事件或完成信号。
3. 事件至少包含可解释的 `event` / `data`；如设计事件 ID，说明其用途。
4. 展示客户端如何接收事件（可用浏览器 `EventSource` 或 `curl -N`，按接口方法与环境选择）。
5. 验证正常完成、异常终止和客户端取消中的至少两种路径；说明实际可验证的边界。
6. 再给出将模拟 `Flux` 替换为 `ChatClient.stream().content()` 的最小接入示例，但默认不执行真实 API 请求。

实践必须提供：文件清单、关键代码、启动命令、请求命令、预期响应、测试方法、验收标准及常见故障排查。代码必须与实际 Spring Boot / Spring AI 版本匹配，不得只给伪代码却声称可运行。

若在真实项目内实践：只读取本练习直接关联的 Controller、Service、配置、依赖和测试；默认禁止全仓库源码读取。跨模块读取必须说明原因。若用户只要求知识笔记，则使用最小独立示例，不擅自修改项目。

## 五、测试与验收

至少设计并执行/给出可执行的验证：
- 响应状态与 `Content-Type: text/event-stream` 正确。
- 多个事件按顺序到达，数据未被意外聚合成单个 JSON 响应。
- 事件格式符合 SSE 约定，UTF-8 内容正常。
- 流正常结束；异常路径能被识别；取消路径不继续无意义地产生数据（以实现可观测范围为准）。
- 说明如何验证慢消费者/背压，以及为什么单次本地请求不能证明生产环境背压能力。

实际执行测试时，完整日志保存本地；向模型提供测试摘要、失败用例和首个根因异常即可，不要默认读取完整日志。不得虚构执行结果；无法执行时标注“未执行”并给出命令。



## 六、输出内容与格式

生成一份 Markdown 笔记，文件名：

`Spring-AI-SSE事件连接生命周期背压与同步响应式边界.md`

优先保存到当前项目已有的知识笔记目录；若无法识别，保存到 `docs/knowledge/`。不得覆盖同名文件；如已存在，先检查并采用安全的新文件名或版本化文件名。

笔记结构：
1. 学习目标与版本基线
2. 一页式全链路图（Mermaid）
3. SSE 协议与原始事件示例
4. 连接生命周期与断连/重连
5. Reactor 背压与取消传播
6. 同步、异步、响应式、流式的区别
7. Spring AI 1.1.x `ChatClient` 流式 API
8. Spring MVC 与 WebFlux 边界对比
9. 最小模拟流式练习（完整可运行示例）
10. 测试、验收与故障排查
11. 常见误区
12. 面试/口述自测题（只列问题，不展开答案）
13. 官方参考资料

表达要求：中文解释，必要术语保留英文；先概念后机制，再到代码与工程判断。避免重复介绍 Reactor 基础，不堆砌无关 API，不扩展成完整 AI 项目架构。

## 七、引用与事实约束

技术结论必须紧贴引用。引用格式统一为：

`English title | 中文翻译标题：[完整 URL](完整 URL)`

优先引用匹配版本的官方资料：
- Spring AI 1.1.x Chat Client API：`https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html`
- Spring Framework 6.2 MVC Asynchronous Requests：`https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html`
- Spring Framework 6.2 WebFlux Controller Return Types：`https://docs.spring.io/spring-framework/reference/6.2/web/webflux/controller/ann-methods/return-types.html`
- Reactor Core Reference Guide：`https://projectreactor.io/docs/core/release/reference/`
- WHATWG HTML Standard - Server-sent events：`https://html.spec.whatwg.org/multipage/server-sent-events.html`

若链接已调整、版本已变化或某个结论无法由该页面支持，必须重新核验并替换为有效的版本匹配来源。不要编造 API、默认值、线程模型、测试结果或性能数据。

## 八、最终验收清单

生成前自检，全部满足才交付：
- [ ] SSE 协议字段与生命周期解释准确。
- [ ] 区分 SSE event、HTTP write、网络分片和模型 token/chunk。
- [ ] 解释 Reactor demand、backpressure、buffer、cancel 的区别。
- [ ] 说明背压不能保证远端模型立即停止生成。
- [ ] 正确区分同步、异步、响应式和流式。
- [ ] 准确说明 MVC 可支持响应式返回与 SSE，但响应写入仍可能阻塞。
- [ ] Spring AI 1.1.x API 与依赖版本经过核验。
- [ ] 有最小模拟流式响应代码、请求方式和测试验收步骤。
- [ ] 技术结论有匹配版本的官方引用。
- [ ] 已生成指定 Markdown 文件，并报告实际保存路径。

**执行要求：直接生成最终知识笔记文件，不要再次输出一份任务书，不要先向用户索要已能从项目或上下文确认的信息。对无法确认的版本、执行结果或兼容性明确标注，不得猜测。**
