# D23 迭代状态

> 本文件依据实际 Git 状态、Maven 测试结果与运行证据维护，不依据历史对话或计划推测。
> 状态取值仅使用：通过 / 失败 / 未验证 / 不适用（附理由）。
> 路径均相对工程根 `M2-Java`。

## 0. 迭代总览

| 项 | 值 |
|---|---|
| 迭代名称 | D23：聊天、SSE、Memory 和结构化输出补齐回归测试 |
| 模块 | D23/D23_CRTFC_SSE_MASO（物理包 com.zl.d23） |
| 技术基线 | JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0、Maven 3.9.16、Servlet/Tomcat + Spring MVC |
| 修复前基线 | 83 测试全通过（退出码 0） |
| 修复后全量回归 | 123 测试全通过（退出码 0，0 失败/0 错误/0 跳过） |
| 生产代码改动 | 无（Git 仅新增 6 个测试类，无已跟踪文件被修改） |
| 真实模型集成 | 未验证（本机无真实 AI_API_KEY，默认回归不触网、不产生费用） |

---

## 需求编号
D23-R01（同步聊天）

## 修改文件
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/AiFailureLifecycleTest.java`（同步失败用例部分）
- 生产代码：无修改（既有 `SyncChatContractTest` 已覆盖正常/参数校验，沿用不改动）

## 设计决定
- 复用既有 `SyncChatContractTest`（5 个 MockMvc 测试）固化正常请求、角色同步、空白/超长 message、非法 owner 等契约，不重复造轮子。
- 针对唯一缺口“同步模型异常时的失败落库与业务码映射”，通过 `@TestConfiguration + @Primary` 注入可控 `AiChatService` 替身，在不触网前提下让 `chat(ChatInvocation, ThinkingSettings)` 抛出 `RuntimeException`，验证全局异常处理返回业务码 5000（SYSTEM_ERROR）且 `data` 为 null，并回查数据库确认用户消息落 `FAILED`。
- 不改变同步接口路径、HTTP 方法、请求/响应字段与业务语义（HTTP 统一 200，靠业务 code 区分）。

## 测试
- 既有 `SyncChatContractTest`（5 方法）：通过
- `AiFailureLifecycleTest#syncException_returnsErrorCodeAndPersistsFailed`：通过
- 执行命令与退出码：
  - 全量 `mvn -f D23/D23_CRTFC_SSE_MASO/pom.xml clean test`：退出码 0

## 证据
- 测试摘要路径：`logs/D23/full-regression.log`（第 658 行 SyncChatContractTest 5 通过；第 194 行 AiFailureLifecycleTest 3 通过）
- 完整日志路径：`logs/D23/full-regression.log`
- 修复前基线：`logs/D23/baseline-test.log`（83 通过）
- Git 报告路径：`logs/D23/git-status.log`

## 遗留问题
- 无。真实模型（remote 模式）同步链路因无 API Key 未执行，标未验证。

---

## 需求编号
D23-R02（SSE 流式聊天）

## 修改文件
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/SseEventOrderTest.java`
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/AiFailureLifecycleTest.java`（SSE error/超时用例部分）
- 生产代码：无修改

## 设计决定
- 真机 RANDOM_PORT + JDK `HttpClient` 读取原始 SSE 文本，自写 `parseSse` 解析命名事件，避免只靠 Mock 验证。
- `SseEventOrderTest` 验证：事件顺序 start 开头/complete 结尾、思考关闭时无 thinking、delta seq 严格从 0 递增、`complete.seq` 等于 delta 数量、`totalChars` 等于各 delta 文本长度之和、start 回传非空 sessionId、delta 拼合为非空回复。
- `AiFailureLifecycleTest` 用可控 Flux 验证两类失败：① 先发一个 delta 再 `Flux.error` → 事件序列 start/delta/error 且无 complete，error 业务码 5000；② `Flux.never()` 配合 `app.ai.stream-timeout-ms=300` 触发应用层 `TimeoutException` → 仅 start/error，error 消息含“超时”。两类均回查确认落 `FAILED`、无 `COMPLETED`。
- 客户端断开/取消沿用既有 `StreamSseHttpTest`（INTERRUPTED 落库），不重复实现。

## 测试
- 既有 `StreamSseHttpTest`（2 方法）：通过；`StreamOrchestratorTest`（3 方法）：通过
- `SseEventOrderTest`（3 方法：event_orderAndDeltaSequence、start_payloadCarriesSession、deltas_joinToNonBlankReply）：通过
- `AiFailureLifecycleTest`（2 相关方法：streamError_emitsErrorEventAndFails、streamTimeout_emitsTimeoutError）：通过
- 执行命令与退出码：全量 `mvn ... clean test`：退出码 0

## 证据
- 测试摘要路径：`logs/D23/full-regression.log`（第 362 行 SseEventOrderTest 3 通过；第 605 行 StreamSseHttpTest 2 通过；第 194 行 AiFailureLifecycleTest 3 通过）
- 完整日志路径：`logs/D23/full-regression.log`
- Git 报告路径：`logs/D23/git-status.log`

## 遗留问题
- 无。容器级超时（stream-timeout + 5000ms 兜底）未单独触发，应用层超时已验证；真实上游 SSE 未验证。

---

## 需求编号
D23-R03（Memory 多轮上下文）

## 修改文件
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/MultiTurnContextWindowTest.java`
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/conversation/ConversationSessionTest.java`
- 生产代码：无修改

## 设计决定
- 权威存储为 MySQL `ChatHistoryService`。`MultiTurnContextWindowTest`（@SpringBootTest MOCK）直接驱动 startTurn/completeTurn/failTurn，断言真实内容与顺序而非方法调用：新会话首轮 PENDING 不入窗（空）；完成一轮后按正序含 user+assistant 且序号递增；同会话第二轮续接后窗口含两轮、角色交替、序号递增且全部属于同一 session；失败轮次不进窗；不同会话互不串线。
- 保留兼容资产 `ConversationSession` 用纯单元测试固化：绑定字段保留、容量内全保留、超 20 条 FIFO 截断、removeLast 非空移除/空返回 false、snapshot 不可变。
- 明确 `ConversationMemory`(Redis) 不进主链路，仅作学习/兼容资产，不纳入主链路断言。

## 测试
- 既有 `SessionIsolationTest`（1 方法，owner 隔离）：通过
- `MultiTurnContextWindowTest`（5 方法：newSession_firstPending_contextEmpty、completedTurn_entersWindowInOrder、secondTurn_sameSession_appendsInOrder、failedTurn_excludedFromWindow、differentSessions_haveIndependentContext）：通过
- `ConversationSessionTest`（6 方法：session_bindingFieldsPreserved、append_withinCapacity_keepsAll、append_beyondCapacity_fifoTruncates、removeLast_nonEmpty_removesAndReturnsTrue、removeLast_empty_returnsFalse、snapshot_isImmutable）：通过
- 执行命令与退出码：全量 `mvn ... clean test`：退出码 0

## 证据
- 测试摘要路径：`logs/D23/full-regression.log`（第 280 行 MultiTurnContextWindowTest 5 通过；第 233 行 ConversationSessionTest 6 通过）
- 完整日志路径：`logs/D23/full-regression.log`
- Git 报告路径：`logs/D23/git-status.log`

## 遗留问题
- 任务书提及“重启后恢复”：本轮测试连接的是同一运行期内的 MySQL 持久化，未做跨进程重启验证（重启恢复依赖真实 MySQL 实例与应用重启），标未验证。历史读取失败（DB 异常）路径未单独模拟，记为后续可补项。

---

## 需求编号
D23-R04（结构化输出）

## 修改文件
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/device/StructuredCandidateParserTest.java`
- 新增 `D23/D23_CRTFC_SSE_MASO/src/test/java/com/zl/d23/device/DeviceEnumFallbackTest.java`
- 生产代码：无修改

## 设计决定
- 针对最大缺口（此前无任何解析器测试），用包内构造器 `new StructuredCandidateParser(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator())` 做纯单元测试，断言最终 DTO 字段与约束，不仅断言“没抛异常”。
- 覆盖：纯 JSON/带围栏/无语言围栏解析、ignoreUnknown 多余字段容忍、三枚举容错（未知 category→UNKNOWN、非 CANDIDATE→NEEDS_CLARIFICATION、未知 source→MODEL_INFERRED、null source→USER_STATED）、小写枚举大小写不敏感、非法 JSON/空白输入抛 `StructuredParseException`（cause 为 Jackson `JsonProcessingException`）、Bean Validation 违例（symptomSummary 空白/超长、deviceModel/faultCode/uncertaintyNote 超长）抛 `IllegalArgumentException` 且指出具体字段、缺失列表由解析器 coalesce 为 `List.of()`。
- `DeviceEnumFallbackTest` 单独固化三枚举 `fromValue` 容错与合法值。
- 关键事实：`StructuredCandidateParser` 虽标 `@Component`，全工程无任何注入/调用（Grep 仅命中自身），真实设备链路 `DeviceChatService` 用确定性 `FaultTicketCandidateService`。按用户约束不删除、不接入，仅补测试固化既有行为。

## 测试
- `StructuredCandidateParserTest`（18 方法）：通过
- `DeviceEnumFallbackTest`（5 方法：ticketCategory_validValues、ticketCategory_fallbacks、candidateStatus_fallbacks、informationSource_unknownFallsBackModelInferred、informationSource_nullReturnsUserStated）：通过
- 执行命令与退出码：全量 `mvn ... clean test`：退出码 0

## 证据
- 测试摘要路径：`logs/D23/full-regression.log`（第 237 行 StructuredCandidateParserTest 18 通过；第 235 行 DeviceEnumFallbackTest 5 通过）
- 完整日志路径：`logs/D23/full-regression.log`
- 单独确认：`logs/D23/unit-3.log`（18 通过，BUILD SUCCESS）
- Git 报告路径：`logs/D23/git-status.log`

## 遗留问题
- `StructuredCandidateParser` 未接入设备主链路属架构现状，是否在后续迭代接入记为新任务项（任务书 3.2 第 6 条），本轮不擅自扩大范围。

---

## 需求编号
D23-R05（关键问题修复）

## 修改文件
- 无生产代码修改。

## 设计决定
- 测试过程中出现的失败经逐一根因核实，全部为测试自身的期望/导入问题，无一是生产代码缺陷，因此不触发生产代码修复，遵守“最小改动、优先根因、不把测试失败当实现缺陷”。
- 测试侧已修正项（均非生产缺陷）：
  1. 非法 JSON 异常实际消息为“模型输出不是合法 JSON，无法生成候选”（原测试误写“结构化候选结果解析失败/包含非法 JSON”），按实际断言并断言 cause 为 `JsonProcessingException`。
  2. 字段校验异常实际前缀为“模型候选字段校验失败”（原测试误写“结构化候选结果校验失败”），按实际断言。
  3. 缺失列表字段：解析器显式将 null coalesce 为 `List.of()`（见 StructuredCandidateParser 第 66-67 行），原断言 isNull 改为 isEmpty。
  4. AssertJ 该 ListAssert 无 `isStrictlyOrdered()`，改用 `isSorted()`。
  5. 两个 SSE 测试漏 `import java.util.stream.Stream`，已补导入。

## 测试
- 不适用（无确认的生产缺陷，故无对应复现—修复闭环）。
- 全量回归 123 通过作为“无回归破坏”的证据：通过。

## 证据
- 历史失败运行日志（保留不删）：`logs/D23/unit-1.log`、`logs/D23/unit-2.log`、`logs/D23/new-all.log`、`logs/D23/new-all-2.log`
- 最终全量：`logs/D23/full-regression.log`

## 遗留问题
- 无生产缺陷遗留。若后续真实模型联调暴露问题，再按 R05 流程关联复现测试与修复证据。

---

## 验收标准（AC）状态矩阵

| 验收编号 | 状态 | 依据/证据 |
|---|---|---|
| D23-AC01 同步契约兼容、回归通过 | 通过 | SyncChatContractTest 5 + AiFailureLifecycleTest 1，full-regression.log |
| D23-AC02 SSE 分块/顺序/完成/错误/取消 | 通过 | SseEventOrderTest 3 + StreamSseHttpTest 2 + AiFailure 2，full-regression.log |
| D23-AC03 Memory 会话关联/顺序/隔离 | 通过 | MultiTurnContextWindowTest 5 + SessionIsolationTest 1 + ConversationSessionTest 6 |
| D23-AC04 结构化合法映射与非法拒绝 | 通过 | StructuredCandidateParserTest 18 + DeviceEnumFallbackTest 5 |
| D23-AC05 关键缺陷复现—修复闭环 | 不适用（本轮无经测试确认的生产代码缺陷，故无修复项；测试侧问题已在 R05 说明） |
| D23-AC06 回归真实执行 | 通过 | 123 测试、退出码 0、完整日志 full-regression.log |
| D23-AC07 无接口破坏/敏感信息/无关改动 | 通过 | git-status.log 仅 6 个新测试文件；敏感信息扫描无新增风险 |
| D23-AC08 两文档从状态与证据生成 | 通过 | docs/proj_docs 两份 D23 正式文档（精确文件名见任务书 7.1） |

## 未验证项汇总（诚实标注）
- 真实模型 remote 模式（含真实 DeepSeek 同步/SSE、真实计费、外网）：未验证，本机无 AI_API_KEY。
- 跨进程重启后的上下文恢复：未验证，依赖真实 MySQL 与应用重启。
- 历史读取失败（DB 异常）分支、容器级超时兜底：未单独模拟，记为后续可补项。

## 证据与日志索引
- 正式文档目录：`docs/proj_docs/`
- 完整日志目录：`logs/D23/`（baseline-test、unit-1/2/3、new-all/new-all-2、full-regression、git-status）
- 临时设计：`docs/temp_docs/D23/D23-test-design.md`
- 本状态文件：`docs/evidence/D23/D23-iteration-state.md`

---
---

# D23 子迭代（二）：Mockito 显式 Java Agent 与 Maven 测试构建规范化

> 本部分为同一 D23 工程下的第二个子迭代（构建规范化），与上方“回归测试子迭代”并存，不覆盖其记录。
> 依据：实际 POM、`mvn help:effective-pom`、`mvn dependency:tree`、Maven Debug fork 参数与真实测试结果。
> 状态取值仅使用：通过 / 失败 / 未验证 / 不适用（附理由）。

## 0. 子迭代总览

| 项 | 值 |
|---|---|
| 子迭代目标 | 保留 Inline Mock Maker，将实际解析的 mockito-core 显式作为测试 JVM 的 -javaagent，统一由 Surefire 管理 |
| 实际 Mockito | mockito-core 5.17.0、mockito-junit-jupiter 5.17.0（test，经 spring-boot-starter-test 3.5.16 传递） |
| Inline 启用方式 | 无 MockMaker 扩展文件、无 mockito-inline；Mockito 5 默认 Mock Maker 即 inline |
| Surefire | maven-surefire-plugin 3.5.6（forkCount 默认 1，forked JVM） |
| Failsafe | maven-failsafe-plugin 3.5.6 仅在 pluginManagement，未在 build/plugins 声明，且无 *IT 测试 → 不执行 |
| 生产代码改动 | 无；仅模块 POM 构建配置变更（26 增 3 删） |
| 全量测试 | 123 通过、0 失败/0 错误/0 跳过，BUILD SUCCESS，退出码 0；动态 Agent 警告 0 命中 |

## 需求编号
D23-R01（保留 Inline Mock Maker）、R02（显式 Java Agent）、R03（Surefire 统一配置）、R04（Failsafe 条件配置）、R05（最小改动）。
> 注：本部分 R01–R05 为构建规范化任务书的编号，与上方回归测试子迭代的 R01–R05 含义不同，已分区避免混淆。

## 修改文件
- 修改（唯一已跟踪文件）：`D23/D23_CRTFC_SSE_MASO/pom.xml`
  - 新增 `maven-dependency-plugin`，execution `resolve-dependency-paths` 绑定 `properties` goal（默认 initialize 阶段）。
  - 现有 `maven-surefire-plugin` 配置内追加 `<argLine>-javaagent:${org.mockito:mockito-core:jar}</argLine>`，保留 systemPropertyVariables。
- 业务 Java、现有测试类：零修改。未新增/升级任何依赖与插件版本（插件版本沿用父 POM pluginManagement）。

## 设计决定
- Mockito 解析版本及来源：mockito-core 5.17.0（test），由 spring-boot-starter-test:3.5.16 传递，BOM 治理，未手动指定版本。
- Agent JAR 路径解析方式：`maven-dependency-plugin:properties` 建立 `org.mockito:mockito-core:jar` 属性，
  实际解析为 `D:\SoftwareInstall\Apache\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar`，
  与测试依赖同版本，不另指定版本。
- Surefire argLine 合并方式：变更前无任何 argLine、无 JaCoCo、无其他占用 JVM 参数的插件，
  故直接在现有 surefire 配置追加，不覆盖既有参数；systemPropertyVariables(spring.profiles.active=test) 原样保留。
- 不使用 `@{argLine}` 延迟替换：无其他插件设置 argLine，直接用 `${org.mockito:mockito-core:jar}` 即可，
  规避字面量 `@{argLine}` 与空初始值问题。
- Fork 核查：`forkCount` 默认值 1（非 0），argLine 对 forked JVM 生效，未改变并发/隔离行为。
- Failsafe 是否存在及处理方式：仅 pluginManagement 有版本/默认目标，build/plugins 未声明、无 *IT/*ITCase 源文件，
  确认不执行，故 R04 不新增 Failsafe、标不适用。
- Inline Mock Maker 保留方式：不新增、不删除、不替换任何 MockMaker 配置；保持 Mockito 5 默认 inline，仅显式挂载 Agent。
- 明确不使用 `-XX:+EnableDynamicAgentLoading`、`-Djdk.instrument.traceUsage` 等仅隐藏警告的参数。

## 测试
- 命令：`mvn -f D23/D23_CRTFC_SSE_MASO/pom.xml clean test -X`（Debug 以捕获 fork 参数），退出码 0。
- 结果：通过。Tests run: 123, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS。
- 警告核查：对 self-attaching / loaded dynamically / EnableDynamicAgentLoading / traceUsage / Dynamic loading of agents 计数 = 0。
- 测试数量（真实报告）：123（21 个测试类，surefire 报告与汇总一致）。

## 证据
- 有效 POM 摘要：`logs/D23/effective-pom-before.xml`（基线）、`logs/D23/effective-pom-after.xml`（变更后，argLine 见 11398/11406 行）。
- 依赖树摘要：`logs/D23/mockito-tree.log`（mockito-core 5.17.0）。
- dependency:properties 解析：`logs/D23/full-test-agent-debug.log` 第 1374、2462 行（属性→实际 JAR 路径）。
- fork JVM Agent 参数摘要：同日志第 2927 行 `(s) argLine = -javaagent:D:\...\mockito-core-5.17.0.jar`；forkCount=1（第 2943 行）。
- 测试报告路径：`D23/D23_CRTFC_SSE_MASO/target/surefire-reports/`（21 份）；汇总见 full-test-agent-debug.log 第 3667 行。
- Git Diff 摘要：`logs/D23/git-diff-after.log`（仅模块 POM，26 增 3 删）；静态检查摘要 `docs/temp_docs/D23/D23-agent-static-check.md`。

## 遗留问题
- 无构建阻塞问题。
- 真实模型 remote 模式仍未验证（本机无 AI_API_KEY），与本构建规范化无直接关系。
- 若未来新增 Failsafe 集成测试，需按 R04 为其独立追加同版本 -javaagent，不得假设 Surefire 配置自动生效。

## 验收标准（AC）状态矩阵（构建规范化）

| 验收编号 | 状态 | 依据/证据 |
|---|---|---|
| D23-AC01 Mockito 版本未被无故改变 | 通过 | mockito-tree.log 前后均 5.17.0，POM 未改版本 |
| D23-AC02 Inline Mock Maker 保留 | 通过 | 无 MockMaker 改动，保持 Mockito 5 默认 inline，相关测试全通过 |
| D23-AC03 Surefire JVM 显式加载 Agent | 通过 | effective-pom-after + debug 第 2927 行 argLine 指向实际 JAR |
| D23-AC04 原有 argLine 参数未丢失 | 通过 | 变更前本无 argLine；systemPropertyVariables 等配置保留（前后对比） |
| D23-AC05 Failsafe 独立配置 | 不适用（Failsafe 未在 build/plugins 声明、无 *IT 测试，不执行；按要求不新增） |
| D23-AC06 单元/集成测试实际执行 | 通过 | 123 通过、退出码 0 |
| D23-AC07 未改业务源码与现有测试逻辑 | 通过 | git-diff-after 仅模块 POM 一个已跟踪文件 |
| D23-AC08 无无关升级、无警告屏蔽参数 | 通过 | 无版本升级；POM 仅含 -javaagent，无 EnableDynamicAgentLoading/traceUsage |

## 本子迭代证据索引
- 正式文档：`docs/proj_docs/D23-项目迭代全量信息与操作-Mockito显式JavaAgent.md`、
  `docs/proj_docs/D23-技术说明与实践指南-Mockito Inline Mock Maker与Surefire配置.md`
- 静态检查：`docs/temp_docs/D23/D23-agent-static-check.md`（temp_docs 被 .gitignore 忽略，仅本地留存）
- 日志目录：`logs/D23/`（*.log 被 .gitignore 忽略；effective-pom *.xml 为未跟踪文件）
- 本状态文件：`docs/evidence/D23/D23-iteration-state.md`（已跟踪，追加方式维护）
