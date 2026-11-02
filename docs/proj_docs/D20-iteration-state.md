# D20 迭代状态

## 需求编号

D20-R01：统一模型调用异常映射、超时配置与有限重试；仅对确认适合重试的失败启用有限重试，不改变既有同步/流式接口契约。

## 修改文件

### 新增主代码（D20_UMIEM/src/main/java/com/zl/d20/resilience/）

- ModelErrorCategory.java：11 值统一异常分类，携带 retryable 与 ResultCode。
- ModelInvocationException.java：携带 category 的统一异常，消息不含上游报文/密钥。
- ModelResilienceProperties.java：前缀 app.ai.resilience，@PostConstruct 范围校验。
- ModelExceptionClassifier.java：cause 链类型匹配 → 状态码正则 → 兜底。
- ModelResilienceSupport.java：唯一重试层，executeBlocking 与 guardFlux。
- ModelClientConfiguration.java：JDK HttpClient + RestClient/WebClient Customizer。

### 修改主代码

- service/DeepSeekAiChatService.java：注入韧性支持；4 处阻塞 call 接 executeBlocking，3 处流式 stream 接 guardFlux。
- common/GlobalExceptionHandler.java：新增 @ExceptionHandler(ModelInvocationException)，按 category.resultCode 映射。
- src/main/resources/application.yml：新增 spring.ai.retry.max-attempts=1 与 app.ai.resilience 完整段。

### 新增测试（D20_UMIEM/src/test/java/com/zl/d20/resilience/）

- ModelExceptionClassifierTest.java（16 方法）
- ModelResilienceSupportTest.java（9 方法）
- ModelResiliencePropertiesTest.java（6 方法）

## 设计决定

- **关闭内置重试**：Spring AI 1.1.8 对 ResourceAccessException 类级一律重试，无法区分建连/读取，且仅覆盖同步、无抖动；置 spring.ai.retry.max-attempts=1 使其退化为透传。
- **应用层单一责任层**：新增 resilience 包作为唯一超时/重试责任层，同步/流式共用分类与配置，杜绝多层叠加。
- **安全重试口径**：建连失败（请求未发出）可重试；读取超时（结果不确定、可能重复计费）默认不重试，由 retry-on-read-timeout 显式控制。
- **流式不重放**：AtomicBoolean emitted 保证首片段后不重试，仅首片段前可有限重订阅。
- **不引新框架**：spring-retry 2.0.13 已由 spring-ai 传递在类路径，不引入 Resilience4j。

## 测试

- ModelExceptionClassifierTest：通过（16/16）
- ModelResilienceSupportTest：通过（9/9）
- ModelResiliencePropertiesTest：通过（6/6）
- 骨架自带 5 测试类（ApplicationTypeTest、SessionIsolationTest、StreamOrchestratorTest、StreamSseHttpTest、SyncChatContractTest）：通过（13/13）
- 实际命令：`mvn -f D20_UMIEM/pom.xml test`，退出码 0；汇总 Tests run: 44, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS。

## 证据

- Git Diff：未执行 git 写操作；基线 HEAD=86f439a8806b65b0d298b8f72a8a56bcfccd2ccf，状态见 docs/temp_docs/D20/D20-git-state.txt。
- 测试摘要：docs/temp_docs/D20/D20-test-summary.txt
- 完整日志：docs/temp_docs/D20/D20-full-test.txt（本地保存，不复制给模型）
- 运行记录：logs/D20/D20-app-run.log
- 真机证据：docs/evidence/D20/（health、sync、stream-raw、frames）

## 遗留问题

- 流式总时限为单次 attempt 级，重试重订阅时重新计时；跨 attempt 硬总边界由 SSE 容器 stream-timeout-ms=60000 兜底，已在双文档诚实标注。
- 未对 ModelInvocationException 单独构造 mock 模式 HTTP 用例；分类→业务码绑定由单元测试覆盖。
- 真实 DeepSeek 联调默认不执行，须用户当轮显式授权后独立手动进行。
