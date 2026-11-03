# D21 迭代状态

## 需求编号

D21-R01：为聊天调用建立最小可观测性——TraceId 关联、脱敏调用记录、单调时钟延迟与 Token Usage 归一化；不改变既有同步/流式接口契约，不引入重型观测框架。

## 修改文件

### 新增主代码（D21_TOKEN_ID_LOGS/src/main/java/com/zl/d21/observability/）

- ModelCallRecorder.java：创建观测上下文；把终态序列化为单行 JSON 输出到 MODEL_CALL logger，异常兜底不抛主流程。
- ModelCallObservation.java：请求级观测，Servlet 线程捕获 requestId/起点；同步 recordSuccess/recordError，流式 instrument；AtomicBoolean 终态守卫。
- ModelCallRecord.java：白名单终态 record，toOrderedMap 固定字段顺序，null 保留。
- CallOutcome.java：SUCCESS / ERROR / CANCELLED 三终态枚举。
- UsageStatus.java：AVAILABLE/PARTIAL/UNAVAILABLE/NOT_RETURNED/NOT_SUPPORTED（本迭代用四态，UNAVAILABLE 预留）。
- UsageSource.java：PROVIDER_METADATA / DERIVED / NONE。
- TokenUsageSnapshot.java：原生 usage 优先、真实判空、total 严格推导、状态/来源归一。
- ModelObservabilityProperties.java：前缀 app.model-observability，enabled 与 recordTimeToFirstChunk。

### 修改主代码

- service/DeepSeekAiChatService.java：构造器注入 ModelCallRecorder；新增 observeBlocking/observeStream 收口，全部同步/流式方法统一接入；删除完整 Prompt/响应日志点。
- src/main/resources/application.yml：新增 app.model-observability 段（enabled、record-time-to-first-chunk，均带环境变量占位）。

### 新增测试（D21_TOKEN_ID_LOGS/src/test/java/com/zl/d21/observability/）

- ModelCallRecorderTest.java（8 方法，ListAppender 断言真实单行 JSON）
- TokenUsageSnapshotTest.java（11 方法，DeepSeek 原生 record + 通用回退）

## 设计决定

- **结构化日志承载最小三支柱**：学习型单体不引 Micrometer/OpenTelemetry，用白名单单行 JSON 同时承载日志、关联与可聚合指标。
- **白名单优于黑名单**：终态记录只写明确允许字段，从结构上排除 Key/Authorization/Cookie/完整 Prompt/响应。
- **跨线程不靠 MDC**：requestId 在 Servlet 线程捕获为对象字段，流式 Reactor 线程直接用字段，避免 ThreadLocal 不传播与串号。
- **单调时钟测耗时**：durationMs/TTFT 基于 System.nanoTime，墙钟仅展示；TTFT 仅在流发射过分片时写出。
- **原生 usage 优先**：规避可移植 Usage 把 null 当 0 的陷阱；缺失保留 null 并给状态，total 仅在 prompt、completion 齐全且无 Provider total 时推导。
- **流式 usage 只覆盖不相加**：以最后一个有效分片为唯一来源，防止累计快照重复计数。
- **观测不影响主流程**：组件旁路记录，emit 全兜底，绝不向业务抛异常。

## 测试

- ModelCallRecorderTest：通过（8/8）
- TokenUsageSnapshotTest：通过（11/11）
- TraceIdHttpTest：通过（5/5）
- D20 resilience 3 类（Classifier 16、Properties 6、Support 9）：通过（31/31）
- 其余骨架类（ApplicationType 2、SessionIsolation 1、StreamOrchestrator 3、StreamSseHttp 2、SyncChatContract 5）：通过（13/13）
- 实际命令：`mvn -f D21_TOKEN_ID_LOGS/pom.xml test`，退出码 0；汇总 Tests run: 68, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS。

## 证据

- 测试逐类汇总：docs/evidence/D21/D21-per-class-summary.txt
- 全量测试完整输出：docs/evidence/D21/D21-full-test-output.txt
- 真机证据：health、sync（header/body）、stream SSE、generated header、敏感扫描（均在 docs/evidence/D21/）
- 运行记录：logs/D21/D21-mock-run.log
- 临时资料：docs/temp_docs/D21/（不自动删除）
- 未执行任何 git 写操作。

## 遗留问题

- 真机为 mock 模式，MODEL_CALL 行由单元测试 ListAppender 断言，未在 mock 运行期产生。
- 通用 Provider 回退若自身把缺失强制为 0，快照无法还原；当前仅接入 DeepSeek。
- UsageStatus.UNAVAILABLE 预留未使用；真实 DeepSeek 联网 usage 与分布式追踪未执行/未接入。
- logback 滚动文件基名仍为历史 d14-access.log，MODEL_CALL 经 root 输出，未单独建 appender。
