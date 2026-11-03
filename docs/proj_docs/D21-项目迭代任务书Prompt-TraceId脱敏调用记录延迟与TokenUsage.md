# D21 项目迭代任务书 Prompt
## TraceId、脱敏调用记录、延迟与 Token Usage



## 1. 本轮参数

| 项目 | 参数 |
|---|---|
| 迭代名称 | D21：聊天请求增加 TraceId、脱敏调用记录、延迟和可获得的 Token usage |
| 项目根目录 | `E:\AI\project\AI-Learning-one\M2-Java\D21` |
| POM | 扫描项目根目录及实际模块中的 `pom.xml`，确认继承、模块和依赖版本 |
| 基础骨架目录 | `E:\AI\project\AI-Learning-one\M2-Java\D21\D21_TOKEN_ID_LOGS` |
| 技术文档目录 | `E:\AI\project\AI-Learning-one\M2-Java\docs\proj_docs` |
| 日志目录 | `E:\AI\project\AI-Learning-one\M2-Java\logs\D21` |
| 临时文件目录 | `E:\AI\project\AI-Learning-one\M2-Java\docs\temp_docs\D21` |
| 证据目录 | `E:\AI\project\AI-Learning-one\M2-Java\docs\evidence\D21` |

正式文档：
- `D21-项目迭代全量信息与操作.md`
- `D21-聊天调用可观测性技术说明与实践指南.md`

临时脚本、索引、摘要和中间文件只允许存入 `docs\temp_docs\D21`。**不得自动删除或清理任何临时文件、历史文件或用户已有文件。**

本轮功能：
1. 为聊天请求增加可贯穿请求处理和模型调用的 TraceId。
2. 记录脱敏后的模型调用信息。
3. 记录可测量的请求延迟。
4. 记录模型实际返回且可获得的 Token usage；无 usage 时不得估算、补造或填入虚假数值。



## 2. 技术约束

默认项目基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、Maven 3.9.16；其他依赖以实际 POM 为准。不得静默升级、降级或覆盖版本。

开始实施前核对 Spring AI 1.1.8 对应官方文档、当前实际 ChatModel/ChatClient 类型、DeepSeek 接入方式和已有同步/流式调用链。不得套用 Spring AI 2.x 属性、API 或未经核验的 Provider 行为。

关键技术事实和核查要求：
- Spring AI 1.1.8 的 `ChatClient` 支持同步与流式调用；可通过 `call().chatResponse()` 获取 `ChatResponse`，其 metadata 可提供 usage；流式分支应检查是否能从 `chatResponse()` 响应流的最终数据中获得 usage。不得仅因使用了 Spring AI 就假定 Provider 一定返回 usage。
- Spring AI 1.1.8 定义了 `Usage` 的 prompt、completion、total token 获取方法。各字段可能缺失，必须逐项判空并记录来源和可用状态。
- Spring AI 1.1.8 的 Observability 需要结合实际依赖和模型实现核验。不得假定 DeepSeek 适配器一定产生全部自动 metrics/traces；自动观测能力与响应 metadata 中实际返回的 usage 是两个不同问题。
- Spring Boot 3.5 的 Micrometer Tracing 可提供 trace/span 上下文及日志关联；是否已经启用必须检查实际 POM 和配置。优先复用，不得重复引入 Tracer、MDC 或自制链路追踪体系。

官方资料（按 Spring AI 1.1.8 / Boot 3.5 对应版本核对）：
- Chat Client API | 聊天客户端 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
- Usage (Spring AI Parent 1.1.8 API) | Usage 用量接口（Spring AI 1.1.8 API）：[https://docs.spring.io/spring-ai/docs/1.1.x/api/org/springframework/ai/chat/metadata/Usage.html](https://docs.spring.io/spring-ai/docs/1.1.x/api/org/springframework/ai/chat/metadata/Usage.html)
- Observability | 可观测性：[https://docs.spring.io/spring-ai/reference/1.1/observability/index.html](https://docs.spring.io/spring-ai/reference/1.1/observability/index.html)
- Tracing | 链路追踪：[https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html)

## 3. 执行协议：轻量扫描与按需读取

### 3.1 本地脚本优先

先检查 `docs\temp_docs`、项目已有工具和索引是否已有扫描脚本。优先复用；缺失时在 `docs\temp_docs\D21` 新建局部扫描脚本，禁止默认读取全仓库源码。

脚本只完成机械任务：
- 检查项目结构、根 POM/子模块 POM、Java/测试文件清单及依赖版本；
- 读取 Git 分支、工作区状态、diff 和变更文件；
- 检查已有项目索引、文件哈希、类/接口/配置/测试引用关系；
- 输出简短索引和本轮功能相关文件候选清单。

完整扫描输出写入本地文件，模型只接收摘要、索引位置和相关文件清单。扫描结果不得包含密钥或完整源码。

### 3.2 定向读取规则

默认禁止全仓库源码读取。先查询索引和引用关系，再读取本轮直接关联的：
- 聊天入口 Controller / Handler；
- Chat Service、ChatClient / ChatModel 适配调用层；
- 同步和流式调用路径；
- Trace / Observation / Logging / MDC 相关配置与组件；
- 调用记录 DTO、实体、Repository 或现有日志封装（仅实际存在时）；
- 对应单元测试、集成测试和配置测试；
- 实际 POM 与本轮使用的配置文件。

需要读取其他模块时，先说明依赖关系及读取原因。只有索引缺失、文件哈希变化、版本变化或结构无法确认时，才执行局部重新扫描。文件未变更时复用已有结论，不重复传输源码。

## 4. 需求分析与设计约束

先确认 D21 是否已有同步/流式接口、Trace/MDC/日志体系、Spring AI 调用方式和异常处理结果。输出简短现状摘要及改动清单，再实施。禁止重复建设已有能力。

### 4.1 TraceId

- 优先复用当前 Spring Boot / Micrometer Tracing 的 Trace 上下文及日志关联能力。
- 每个入站聊天请求应能关联到其模型调用日志；同步和 Flux 流式路径均需验证上下文传递。
- 若已有 tracing，使用当前 traceId，不另造一套并行 ID。
- 若项目没有完整分布式 tracing，只能按现有约定生成 request/correlation ID，并明确它不是 W3C 分布式 trace；是否增加 tracing 依赖必须有实际必要性和版本依据。
- 如接收外部 Trace 相关 Header，核验格式、长度并防止日志注入；无效值按既有标准拒绝或重新生成。不要盲目信任任意字符串。
- 在约定响应 Header（如项目现有规范允许）和结构化日志中回传/记录 ID；请求结束后正确清理上下文。
- Reactor/异步线程中不得依赖未经验证的 ThreadLocal 传播；检查 Reactor Context / Micrometer context propagation，防止串号和上下文泄漏。

### 4.2 脱敏调用记录

每个逻辑模型调用至少产生一条结构化终态记录；成功、失败和客户端取消均应有明确状态。优先复用现有日志封装或调用记录机制。不要默认新增数据库表；只有现有需求确实要求查询、审计或持久化，并且项目没有可复用机制时，才提出数据表方案及必要性。

建议的字段白名单（按项目现状裁剪）：
- `traceId` / `requestId`；
- `provider`、`model`、调用模式（sync/stream）；
- 开始时间、结束时间、耗时；
- 终态（SUCCESS / ERROR / CANCELLED）；
- 安全的错误分类/错误码；
- Token usage 状态、来源及实际可获得的 token 数；
- Provider response id（仅实际返回且不含敏感信息时）。

禁止记录：API Key、Authorization Header、Cookie、完整 Prompt、用户原文、完整模型响应、敏感业务字段、未经脱敏的异常请求体。默认使用字段白名单而非仅依赖黑名单替换。确需保留业务标识时，遵循现有权限和脱敏规则，避免在 metrics 标签中使用高基数 ID。

### 4.3 延迟

- 使用单调时钟（如 `System.nanoTime()`）测量耗时，不用系统墙上时间相减计算 duration。
- 同步调用记录模型调用开始至结束/异常的耗时。
- 流式调用记录开始至终态（完成、错误或取消）的总耗时；首 Token/首 Chunk 延迟只有在可准确测量时才记录。
- 明确单位为毫秒，区分模型调用耗时和完整 HTTP 请求耗时，不混为同一指标。
- 取消、异常和超时路径也必须终结记录；避免成功/失败重复写两条终态记录。

### 4.4 Token Usage：严禁伪造

- 优先读取实际 `ChatResponse.getMetadata().getUsage()`，根据 Spring AI 1.1.8 API 判空读取 prompt、completion、total tokens；结合真实 Provider 响应检查 DeepSeek 当前调用路径是否返回这些字段。
- 同步调用如当前代码只取 `.content()` 导致 metadata 丢失，评估在服务层改用 `chatResponse()` 获取内容和 metadata；不得破坏现有接口契约。
- 流式调用检查 `.chatResponse()` 数据流、最终 chunk 和 Provider usage 返回行为。若现有链路只暴露 `Flux<String>`，评估最小内部调整以保留 metadata，同时保持 D21 对外 SSE 协议兼容。
- 每个字段可独立缺失。建议记录 `usageStatus`（AVAILABLE / PARTIAL / UNAVAILABLE / NOT_RETURNED / NOT_SUPPORTED）和 `usageSource`（PROVIDER_METADATA / DERIVED / NONE）。
- 只允许记录 Provider 实际提供的数值。若 prompt 与 completion 两项都由 Provider 返回，可按明确规则计算 total，并标记为 `DERIVED`；否则 total 留空。不得根据字符数、字数、经验比例或本地估算器伪装成 Provider usage。
- 无 usage 时使用 `null` / 缺省字段及状态说明，不能填 0 冒充真实用量。0 只有在 Provider 明确返回 0 时才可记录。
- 流式场景避免把多个 chunk 中重复出现的累计 usage 相加；根据真实协议确定最终 usage 的唯一来源，防止重复统计。
- 区分单次调用 usage 与整条业务链累计 usage；本轮只统计明确边界内的模型调用，避免把重试或多步调用漏记/重复计数。

## 5. 最小实施步骤

1. 读取并记录原始附件是否存在；查看项目索引、POM 摘要、Git 状态及 D21 相关调用链。
2. 输出本轮实际现状、直接关联文件、复用点和最小修改清单；对不确定的 Provider usage 行为给出核查方式。
3. 实施 TraceId 关联、脱敏结构化调用记录、延迟测量和 usage 提取；尽量复用现有框架与配置。
4. 补齐同步与流式路径测试；执行敏感信息扫描、Git diff 和必要回归。
5. 每完成一个功能切片，更新结构化迭代状态记录。
6. 从结构化记录、Git diff、实际测试摘要和运行证据生成两份正式文档。

不引入与本轮无关的数据库、监控平台、消息队列或新框架。是否新增 Actuator / Micrometer Tracing 依赖，必须先检查现状并说明必要性；禁止为展示指标而重复安装同类组件。

## 6. 必须执行的测试

测试由本地脚本/构建工具执行。完整日志写入 `logs\D21`，不要把完整日志交给模型。模型默认只读取：测试摘要、失败用例、首个根因异常和必要堆栈；只有诊断确有需要时才读取指定日志片段。

至少覆盖：
1. 请求无 TraceId 时生成/关联成功；已有有效 TraceId 时沿用现有 tracing 语义。
2. 无效或恶意 Header 不造成日志注入；请求间 TraceId 不串号。
3. 同步成功、模型异常时均产生正确的脱敏终态记录。
4. 流式正常完成、上游错误、客户端取消时，终态与总耗时正确且只记录一次。
5. Prompt、Completion、API Key、Authorization、Cookie 和敏感异常内容不会进入日志。
6. Usage 完整返回、部分字段缺失、完全不返回时，状态和字段正确；缺失时绝不生成估算数值或用 0 填充。
7. 流式多 chunk 不重复累计 usage；同步和流式的 usage 统计边界清晰。
8. 原同步/流式接口回归通过，响应契约不变。

测试失败不得通过删除断言、跳过测试或降低覆盖来“修绿”。

## 7. Token 成本与 Agent 执行限制

不得以牺牲代码质量、必要测试、安全或文档质量节省 Token。执行中：
- 复用索引、哈希、Git diff 和已读取且未变化的文件结论；禁止重复全仓扫描、重复读取和多 Agent 重复分析同一模块。
- 每阶段输出短交接记录，不重复贴完整源码、完整日志和完整架构说明。
- 同一根因连续两次修复仍失败，立即停止修复循环，输出诊断记录：现象、复现步骤、根因假设、已尝试修改、失败证据、下一步验证方案；由用户/负责人决定是否继续。
- 单模块初始目标 130万–180万 Token；跨模块 180万–300万 Token。300万触发统计检查，400万触发人工复核，500万暂停无效 Agent 循环并重新规划。不得为了预算阈值跳过必要工程工作。
- 尽可能记录请求数、输入 Token、缓存命中/未命中 Token、输出 Token、模型/会话、执行阶段、累计 Token 和费用。统计不记录 API Key、完整 Prompt 或敏感数据。

## 8. 结构化迭代状态记录

每个功能切片完成后，维护 `docs\evidence\D21\D21-iteration-state.md`（若文件已存在则增量更新，不覆盖无关记录）：

```markdown
# D21 迭代状态

## 需求编号
- D21-R01 TraceId 贯穿聊天请求
- D21-R02 脱敏模型调用记录
- D21-R03 请求/模型延迟记录
- D21-R04 实际 Token usage 提取及缺失标记

## 修改文件
- 实际路径列表

## 设计决定
- 复用的 tracing/logging/AI 调用组件及原因
- usage 来源、缺失处理和流式终态处理

## 测试
- 测试类/用例：实际结果
- 执行命令与测试摘要文件路径

## 证据
- Git diff、运行记录、敏感信息扫描、测试报告的实际路径

## Token 统计
- 请求数、输入/缓存/输出 Token、模型/阶段、累计 Token、费用（有数据才填）

## 遗留问题
- 无 / 具体问题及影响
```

未执行或无法确认的项目标记为“未验证”，不得写“通过”。

## 9. 交付与验收

### 文档一：D21 项目迭代全量信息与操作

记录需求编号、现状和复用结论、修改文件、设计决定、配置、接口行为、测试命令和摘要、Git diff、运行证据、未完成事项。只写本轮实际发生的变化，不重复原有架构说明。

### 文档二：D21 聊天调用可观测性技术说明与实践指南

解释 TraceId 与请求关联、日志字段及脱敏、延迟定义、Spring AI 1.1.8 Usage 读取方式、Provider/流式 usage 的限制、配置和复现步骤、常见问题。区分官方事实、项目实测和未验证行为。

### 最终验收矩阵

| 编号 | 验收要求 | 实际结果 | 证据路径 | 状态 |
|---|---|---|---|---|
| D21-R01 | TraceId 在同步/流式请求中正确关联 | 实测填写 | 日志/测试 | 通过/失败/未验证 |
| D21-R02 | 调用记录脱敏且不泄露敏感内容 | 实测填写 | 日志/扫描报告 | 通过/失败/未验证 |
| D21-R03 | 延迟口径正确，成功/失败/取消均记录 | 实测填写 | 测试/运行记录 | 通过/失败/未验证 |
| D21-R04 | Usage 只记录实际可获得数值，缺失不伪造 | 实测填写 | 测试/Provider 响应摘要 | 通过/失败/未验证 |
| D21-R05 | 原有接口兼容，必要回归通过 | 实测填写 | 测试报告 | 通过/失败/未验证 |
| D21-R06 | Git Diff、敏感信息扫描和两份文档完成 | 实测填写 | 证据目录 | 通过/失败/未验证 |

完成报告必须注明：实际执行的测试命令、通过/失败/跳过项、日志摘要路径、变更文件、Git 状态/Commit ID（真实存在时）、两份文档路径、证据目录和遗留问题。没有真实证据不得判定通过。

## 10. 停止条件

遇到项目结构与索引不符、关键依赖或 Provider 行为无法确认、测试失败且根因不明、敏感信息泄漏风险、用户已有修改可能被覆盖时，停止相关操作并记录原因。不得擅自清理工作区、删除临时资料、重置 Git、覆盖用户文件或推送远程分支。

**现在开始 D21：先索引和定向调查，再设计与最小实现；真实运行测试；按结构化记录生成两份文档。不得全仓读源码，不得将完整日志输入模型，不得伪造 TraceId、延迟或 Token usage。**
