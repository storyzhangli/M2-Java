# Spring AI M2 知识笔记：Token 用量、模型耗时、请求 ID、结构化日志与敏感日志风险

> 版本核验日期：2026-11-03（Asia/Singapore）  
> 目标：掌握一次 Spring AI 模型调用的 Token Usage、耗时和请求关联信息采集；能输出安全的结构化日志与样本记录。  
> 范围：知识学习与最小 Mock 实践，不执行真实 DeepSeek API 调用，不涉及完整可观测性平台建设。

## 1. 技术基线与核验结论

| 项目 | 本笔记基线 | 核验结论 |
|---|---|---|
| JDK | 21 | 满足 Spring Boot 3.5.16 要求 |
| Spring Boot | 3.5.16 | 官方 3.5 文档对应版本 |
| Spring AI | 1.1.8 | 当前 1.1.x 文档与 Javadoc 对应 1.1.8 |
| Maven | 3.9.16 | 作为项目构建工具版本；依赖版本由 BOM 管理 |
| Micrometer | Spring Boot BOM 管理 | 不单独猜测或覆盖版本，使用 `mvn dependency:tree` 核对最终解析版本 |
| 模型服务背景 | DeepSeek | 仅使用 Mock 响应练习，不请求真实 API |

Spring AI 1.1.8 官方文档明确支持 Spring Boot 3.4.x 和 3.5.x，因此与 Boot 3.5.16 基线匹配。Boot 3.5.16 支持 Java 17 及以上，JDK 21 可用。

**重要版本边界：**本笔记中的 Spring AI API 以 1.1.8 为准，不引用 2.x 才有的 API 或默认行为。Spring AI 1.1.8 Observability 文档列出的 ChatModel 自动观测支持实现名单没有列出 DeepSeek；但 1.1.8 的 API 中存在 DeepSeek 模型实现，且 `AiProvider` API 有 DeepSeek 枚举。两者不能简单等同：`ChatResponseMetadata` 的 Usage 读取 API 可以使用，但不能据此推断 DeepSeek 一定自动产生完整的 Spring AI Metrics/Trace。需在目标项目用实际 `MeterRegistry` 验证。

## 2. 概念地图

一次模型调用应当同时观察三类信息：

| 类型 | 回答的问题 | 典型字段 | 主要用途 |
|---|---|---|---|
| Token Usage | 本次调用消耗了多少 Token？ | input/output/total tokens | 用量、成本分析、限额 |
| Latency | 调用花了多长时间？ | duration、TTFT、stream duration | 性能监控、SLA、慢请求诊断 |
| Correlation | 这条日志属于哪个业务请求和调用链？ | requestId、traceId、spanId、providerResponseId | 日志检索、跨组件排查 |

三者不能互相替代：Token 数不等于耗时；requestId 不等于 traceId；一条日志也不等于一条 Metric 或一个 Trace。

## 3. Token 用量

### 3.1 三个基础指标

- **Input Tokens / Prompt Tokens：**发送给模型的输入 Token。通常包括 system message、user message、历史对话、RAG 检索上下文、工具定义和本次请求中携带的其他输入内容。
- **Output Tokens / Completion Tokens：**模型生成的输出 Token。具体是否包含推理相关 Token、工具调用内容等，以供应商 API 对 Usage 的定义为准。
- **Total Tokens：**本次请求的输入与输出 Token 总数，通常满足 `total = input + output`。以供应商返回的 total 为优先，不要在字段缺失时伪装成服务端上报值。

Token 不等于字符数、单词数或 UTF-8 字节数。中文、英文、代码、JSON、标点符号和不同模型的 Tokenizer 切分方式不同，不能使用 `String.length()` 计算真实 Token 数。

### 3.2 统计口径必须先定义

1. **单次 ChatModel 调用：**一次实际模型请求及其响应，最适合与 Provider Usage 对齐。
2. **一次 ChatClient 业务操作：**可能包含 Advisor、RAG 检索、工具调用流程；不一定等于一次 ChatModel 请求。
3. **多轮对话：**`conversationId` 是会话标识。每轮通常是独立模型请求，历史消息可能重复进入后续轮次的 Prompt，因此不能把会话轮数直接当 Token 数。
4. **Tool Calling：**模型可能先发起工具调用，工具执行后再请求模型。每次模型往返都可能产生独立 Token Usage；如需统计整个业务任务，要汇总所有实际模型调用，不要只看最后一个响应。
5. **Retry：**框架或 HTTP 层重试可能产生多次 Provider 请求。应区分业务调用次数、模型请求尝试次数和最终成功次数。不要在没有重试观测证据时把 `retryCount` 写成 0。

### 3.3 Spring AI 1.1.8 获取 ChatResponse Usage

使用 `ChatClient` 时，如果只调用 `.content()`，调用方只得到字符串内容，通常无法通过该返回值读取完整响应元数据。需要 Token Usage、模型名、响应 ID 时，使用 `.call().chatResponse()`：

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

ChatResponse response = chatClient.prompt()
        .user("请用一句话解释什么是 Redis 缓存穿透。")
        .call()
        .chatResponse();
```

Spring AI 1.1.8 的读取路径：

```java
ChatResponseMetadata metadata = response.getMetadata();
String responseId = metadata == null ? null : metadata.getId();
String model = metadata == null ? null : metadata.getModel();
Usage usage = metadata == null ? null : metadata.getUsage();

Integer inputTokens = usage == null ? null : usage.getPromptTokens();
Integer outputTokens = usage == null ? null : usage.getCompletionTokens();
Integer totalTokens = usage == null ? null : usage.getTotalTokens();
Object nativeUsage = usage == null ? null : usage.getNativeUsage();
```

这些 API 在 Spring AI 1.1.8 中存在：

- `ChatResponse.getMetadata()` 获取响应元数据。
- `ChatResponseMetadata.getId()`、`getModel()`、`getUsage()` 分别获取响应标识、模型名、Usage。
- `Usage.getPromptTokens()`、`getCompletionTokens()`、`getTotalTokens()`、`getNativeUsage()` 均存在；Token getter 返回 `Integer`，因此必须处理 `null`。

**不要记录 `nativeUsage.toString()`。**`getNativeUsage()` 是供应商原生对象，数据结构和字段随模型实现而变化。需要**供应商特有字段**时，应针对实际类或经过验证的 Map 白名单读取数字字段，不要把整个对象写日志。

### 3.4 Usage 缺失、估算和账单数据

| 数据来源 | 含义 | 可否称为真实模型用量 |
|---|---|---|
| `ChatResponseMetadata.getUsage()` | Spring AI 响应中映射的 Provider Usage | 有对应响应字段时可以；保留来源标记 |
| 本地 Tokenizer 估算 | 根据模型 Tokenizer 或近似算法估算 | 不可以，只能标记 `ESTIMATED` |
| Provider 控制台 / 账单 | 服务商按其计费口径汇总的用量与费用 | 作为账单核对来源；不等同于单次响应字段 |
| Mock / 合成数据 | 测试中主动构造的示例值 | 不可以，必须标记 `MOCK` 或 `synthetic` |

推荐规则：

- Usage 对象为空：Token 记 `null`，`usageSource=UNKNOWN`。
- Usage 存在但某一项字段为空：仅该字段记 `null`，不要把缺失值补成 0。
- 若使用本地估算：单独写 `estimatedInputTokens` 等字段，或至少标记 `usageSource=ESTIMATED`，不要伪装成 Provider Usage。
- Provider Usage 和账单金额分开存储。实际费用还可能受缓存、模型价格、区域、优惠和计费规则影响。

### 3.5 DeepSeek Usage 的供应商特有字段

DeepSeek Chat Completions API 当前文档包含标准 `prompt_tokens`、`completion_tokens`、`total_tokens`，以及输入缓存拆分字段（如 `prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`、`prompt_tokens_details.cached_tokens`）和可选的 `completion_tokens_details.reasoning_tokens`。

注意：这些是 DeepSeek API 的原生字段说明，不代表 Spring AI 1.1.8 的通用 `Usage` 接口会为每个供应商特有字段提供独立 getter。`Usage` 通用层只承诺 prompt/completion/total 和 native usage 对象。需要缓存命中数或 reasoning tokens 时，应核对 `getNativeUsage()` 的真实类型与对应 1.1.8 DeepSeek 映射实现，再单独做白名单适配。不得硬编码猜测 `nativeUsage` 一定是某个 Map 或 DTO。

## 4. 模型调用耗时

### 4.1 先明确计时边界

| 耗时 | 起点 | 终点 | 代表含义 |
|---|---|---|---|
| HTTP 业务请求总耗时 | Controller / Filter 入口 | HTTP 响应完成 | 服务端整体处理成本，可能包含鉴权、数据库、RAG、模型、序列化 |
| ChatClient 操作耗时 | ChatClient 操作真正执行前 | 返回完整响应或流终止 | 可能包含 Advisor 与模型调用相关流程 |
| ChatModel 调用耗时 | ChatModel `call()` / `stream()` 执行前 | 同步调用完成或流终止 | 更接近模型调用层的耗时 |
| Retry 单次尝试耗时 | 每次底层尝试开始 | 该次尝试结束 | 用于识别重试、网络抖动和 Provider 错误 |

`call()` 的 fluent API 构造阶段不应被误当作模型调用完成。实际调用在取得响应的终结操作（如 `.chatResponse()`）处发生。外层计时应覆盖真正执行的部分。

### 4.2 同步调用：System.nanoTime 与 Micrometer Timer

使用 `System.nanoTime()` 测量一段代码的经过时间：

```java
long startedNanos = System.nanoTime();
try {
    ChatResponse response = chatClient.prompt()
            .user("模拟请求")
            .call()
            .chatResponse();
    // 处理响应
} finally {
    long elapsedNanos = System.nanoTime() - startedNanos;
    long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS
            .toMillis(elapsedNanos);
}
```

- `System.nanoTime()` 是用于测量经过时间的单调时钟，不代表日历时间。
- `System.currentTimeMillis()` 表示 Unix epoch 毫秒，适合时间戳，不适合精确测量耗时。
- 日志中的 `timestamp` 使用 `Instant.now()`；耗时使用 `nanoTime` 差值。

更推荐用 Micrometer `Timer` 汇总延迟和调用次数：

```java
Timer.builder("app.llm.chatclient.operation")
        .description("Time spent in one ChatClient operation")
        .tags("provider", "deepseek", "model", "configured-model", "status", "SUCCESS")
        .register(meterRegistry)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
```

Timer 的单位由 Micrometer 根据 Registry/后端处理；调用 `record` 时传入明确的 `TimeUnit`。不要在指标标签中加入 `requestId`、`traceId`、`conversationId`、用户 ID 或设备 ID。

### 4.3 P50、P95、P99

- **P50 / 中位数：**一半请求耗时不超过该值，反映典型请求。
- **P95：**95% 请求耗时不超过该值，用于观察长尾体验。
- **P99：**更关注极端慢请求，容易受网络抖动、排队和偶发 Provider 延迟影响。
- **Average：**总耗时除以调用次数，容易被大量快速请求掩盖长尾。

Micrometer Timer 默认至少能记录总时间与事件数；最大值、直方图和百分位能力取决于 Registry/后端配置。要计算可聚合的 P95/P99，应针对合适的 Meter 配置 percentile histogram 并由监控后端计算；不要假设未配置直方图时自然就能得到准确 P95/P99。

### 4.4 流式调用：TTFT 与结束时间

流式调用至少应区分：

- **TTFT（Time To First Token）：**从订阅/请求发起到第一个非空输出片段到达的时间。
- **Stream Duration：**从订阅/请求发起到流完成、异常或取消的总时间。
- **Completion Time：**正常收到终止信号的时间点。
- **Cancellation：**客户端断开或主动取消；不能标记为正常成功完成。

Spring AI 1.1.x `ChatClient` 支持 `stream().chatResponse()` 返回 `Flux<ChatResponse>`。计时应从订阅开始，收到第一个非空输出时记录 TTFT，在 `onComplete`、`onError`、`onCancel` 中分别记录结束状态。不能把 `Flux` 对象刚返回的时刻当成模型响应完成时间，也不能把每个 delta 当成一次独立模型调用。

流式 Usage 是否存在、位于哪个 chunk、是否只在最后一个 chunk 返回，取决于供应商和流式协议。必须遵循实际 Provider 协议；不要把多个 chunk 中重复出现的 usage 直接累加，否则可能重复计数。没有最终 Usage 就记录 `unknown`。

## 5. requestId、traceId、spanId、Provider Response ID 与 conversationId

| 标识 | 作用范围 | 生成方 / 来源 | 不应被误认为 |
|---|---|---|---|
| `requestId` | 一次应用业务请求 | 应用入口生成，或经过信任边界校验的上游传入 | 分布式 Trace ID |
| `traceId` | 一个跨服务调用链 | Micrometer Tracing / OpenTelemetry / Brave | 一次模型调用 ID |
| `spanId` | Trace 中的一个操作片段 | Tracer | 整个 Trace ID |
| `providerResponseId` | Provider 返回的一次模型响应标识 | 响应元数据，如 `ChatResponseMetadata.getId()` | 本地 requestId 或 conversationId |
| `conversationId` | 一段多轮对话 / Chat Memory | 业务系统 | 单次 HTTP 请求或单次模型调用 |

requestId 与 traceId 可以在日志中同时出现：requestId 方便定位业务请求；traceId/spanId 用于追踪跨组件和跨服务调用。没有 Tracer 时，traceId/spanId 可以为空，不能自行生成一个随机字符串冒充 Trace。

### 5.1 Servlet 同步请求中的 requestId 与 MDC

`MDC` 通常基于 ThreadLocal。它适合在同一执行线程的同步调用中关联日志，但不会自动跨任意线程池、异步回调或 Reactor 线程切换传播。

一个简单的 Servlet Filter 示例：

```java
package com.example.m2.web;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();

        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("requestId", requestId);
            response.setHeader(HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            // 线程池会复用线程，必须恢复或清理 MDC，不能污染下一个请求。
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
```

说明：

- Header 值必须限制长度和字符集，防止 CR/LF 日志注入。
- 只有来自可信网关的上游 requestId 才应被信任；客户端提供的 ID 不能用于认证、授权或防重放。
- 不管是否接受上游 ID，都应在响应中返回应用最终采用的 requestId，便于客户端反馈问题。
- 上述 Filter 示例覆盖 Servlet 同步请求；异步任务、线程池和 WebFlux/Reactor 必须另行处理上下文传播。


#### Filter 的 MDC 清理测试

测试类与 Filter 放在同一个 `com.example.m2.web` 包中：

```java
package com.example.m2.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @Test
    void shouldCreateExposeAndClearRequestId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> captured = new AtomicReference<>();

        FilterChain chain = (servletRequest, servletResponse) ->
                captured.set(MDC.get("requestId"));

        filter.doFilter(request, response, chain);

        assertNotNull(captured.get());
        assertEquals(captured.get(), response.getHeader("X-Request-Id"));
        assertNull(MDC.get("requestId"));
    }
}
```

该测试需要 `spring-boot-starter-test` 中的 Spring Test Mock Servlet 类。

### 5.2 异步与 Reactor 上下文

- `@Async` / 自建线程池：可通过 `TaskDecorator` 捕获当前 MDC，任务执行前恢复，完成后还原或清理。
- Reactor：业务上下文应以 Reactor Context 为主要承载；跨 ThreadLocal 与 Reactor Context 的边界可使用 Micrometer Context Propagation 的 `ThreadLocalAccessor`、`ContextSnapshot` 等能力。
- 不要只依赖 `MDC.put()`，然后假设线程切换后 requestId/traceId 仍然存在。
- Spring Boot + Micrometer Tracing 能将 traceId/spanId 放入 MDC 并生成日志 correlation ID，但需要配置 Tracer 实现和传播链路；未启用 Tracing 时不要假设这些字段一定存在。

## 6. 结构化日志

### 6.1 为什么不只写普通字符串

普通文本日志适合人眼快速阅读；结构化 JSON 日志将字段独立保存，便于日志平台按照 `requestId`、`model`、`status`、`durationMs` 等字段检索、聚合和告警。

Spring Boot 3.5.16 内置支持 ECS、GELF、Logstash 三种结构化 JSON 格式。最小配置示例：

```yaml
spring:
  application:
    name: m2-spring-ai-demo

logging:
  structured:
    format:
      console: logstash
      file: logstash
```

如果使用自定义 Logback/Log4j2 配置，需确认 Encoder 使用 Spring Boot 对应的 structured logging 配置变量；不能假设自定义 XML 自动继承默认配置。

### 6.2 推荐模型调用日志字段

| 字段 | 类型 | 含义 / 规则 |
|---|---|---|
| `timestamp` | ISO-8601 字符串 | 事件时间，建议带时区或使用 UTC |
| `level` | 枚举字符串 | `INFO`、`WARN`、`ERROR` |
| `event` | 枚举字符串 | 如 `llm.call.completed`、`llm.call.failed` |
| `requestId` | 字符串 | 当前应用请求标识 |
| `traceId` | 字符串或 null | Tracing 可用时记录 |
| `spanId` | 字符串或 null | Tracing 可用时记录 |
| `provider` | 低基数字符串 | 如 `deepseek`、`mock` |
| `model` | 受控字符串 | 实际模型名或配置模型名；限制来源和长度 |
| `operation` | 枚举字符串 | 如 `chat`、`embedding` |
| `streaming` | boolean | 是否流式调用 |
| `durationMs` | long | 本事件定义边界内的耗时，单位毫秒 |
| `inputTokens` | Integer 或 null | Usage 未知时为 null |
| `outputTokens` | Integer 或 null | Usage 未知时为 null |
| `totalTokens` | Integer 或 null | Usage 未知时为 null |
| `usageSource` | 枚举字符串 | `PROVIDER_RESPONSE_METADATA`、`ESTIMATED`、`MOCK`、`UNKNOWN` |
| `providerResponseId` | 字符串或 null | 仅记录响应 ID，不记录完整响应对象 |
| `status` | 枚举字符串 | `SUCCESS`、`ERROR`、`CANCELLED` |
| `errorCode` | 归一化错误码或 null | 不记录原始异常消息和敏感请求内容 |
| `retryCount` | Integer 或 null | 只有拿到实际重试次数才记录；未知时为 null |

**日志（Log）、指标（Metric）、追踪（Trace）职责不同：**

- Log：单次事件的元数据明细，支持按 requestId 排查单个请求。
- Metric：聚合计数、延迟分布和 Token 用量趋势，用于监控和告警。
- Trace：跨服务、跨组件展示一次请求经历的 Span 及耗时关系。

不要将 requestId、traceId、conversationId、用户 ID、设备 ID、工单号或 providerResponseId 作为常规 Metric 标签。这些值基数高，会造成时间序列数量膨胀。它们适合放在受访问控制的日志或 Trace 属性中。

### 6.3 使用 SLF4J key-value 写日志，不拼接整个对象

```java
log.atInfo()
        .addKeyValue("timestamp", Instant.now().toString())
        .addKeyValue("event", "llm.call.completed")
        .addKeyValue("requestId", requestId)
        .addKeyValue("traceId", traceId)
        .addKeyValue("provider", provider)
        .addKeyValue("model", model)
        .addKeyValue("operation", "chat")
        .addKeyValue("streaming", false)
        .addKeyValue("durationMs", durationMs)
        .addKeyValue("inputTokens", inputTokens)
        .addKeyValue("outputTokens", outputTokens)
        .addKeyValue("totalTokens", totalTokens)
        .addKeyValue("usageSource", usageSource)
        .addKeyValue("providerResponseId", providerResponseId)
        .addKeyValue("status", status)
        .addKeyValue("errorCode", errorCode)
        .addKeyValue("retryCount", retryCount)
        .log("LLM call metadata recorded");
```

不要使用 `log.info("response={}", response)`、`response.toString()`、`prompt.toString()` 或 `exception.getMessage()` 作为排障捷径。它们可能将消息正文、Prompt、Tool 参数、Provider 原始对象或敏感请求信息写入日志。

## 7. 敏感日志风险与治理

### 7.1 禁止默认记录

| 敏感项 | 风险 | 默认处理 |
|---|---|---|
| API Key、Authorization、Bearer Token | 凭据泄露、账户被盗用 | 绝不记录；Header 全量排除 |
| Cookie、Session、访问令牌 | 会话劫持 | 绝不记录原文 |
| 数据库密码、Redis 密码、私钥 | 基础设施被入侵 | 绝不记录 |
| 完整 Prompt、系统提示词、聊天历史 | 个人信息、业务秘密、内部规则泄露 | 默认不记录 |
| 模型完整回答、推理内容 | 可能包含敏感业务数据或内部信息 | 默认不记录 |
| 工单内容、设备序列号、故障描述 | 业务机密、设备资产信息 | 记录白名单元数据，不记正文 |
| Tool Calling 参数及结果 | 可能包含账号、订单、设备或客户数据 | 默认不记录参数和结果正文 |
| 请求头、URL 查询参数 | Token、手机号、邮箱等可能出现在其中 | 只允许白名单字段 |
| 异常原文 / 完整堆栈 | 可能带出 URL、Header、SQL 或响应体 | 归一化 errorCode；必要的堆栈需受控采集 |

### 7.2 记录白名单，而不是先记录再脱敏

推荐做法：

1. 为模型调用日志定义固定 DTO / 字段白名单。
2. 只记录 requestId、traceId、provider、model、耗时、Token 数、状态和归一化错误码等元数据。
3. 不将 `Prompt`、`ChatResponse`、`Usage.getNativeUsage()`、HTTP 请求对象或异常对象整体序列化。
4. 供应商原始响应需要排查时，在隔离测试环境使用合成数据；生产环境需有明确审批、访问控制、脱敏和保留期限。
5. 对日志平台设置权限分级、查询审计、导出控制和保留周期；日志文件也需要最小化文件系统权限。
6. 限制外部传入字段的长度与字符集，防止日志注入和超大日志。
7. 通过日志级别、采样和事件白名单降低日志量，避免把每个流式 token/chunk 作为一条完整业务日志。

### 7.3 Spring AI 内容日志必须显式保持关闭

Spring AI 1.1.8 的官方配置项包括：

```yaml
spring:
  ai:
    chat:
      client:
        observations:
          log-prompt: false
          log-completion: false
      observations:
        log-prompt: false
        log-completion: false
        include-error-logging: false
```

`ChatClient` 层与 `ChatModel` 层的 Prompt/Completion 内容日志默认关闭。不要在生产环境打开这些开关来代替安全的元数据日志。

同时不要在生产 ChatClient 上随意添加 `SimpleLoggerAdvisor`。它用于记录 ChatClient request/response，调试价值和敏感信息风险并存。即使使用自定义 Logger 函数，也必须以字段白名单为准，绝不能输出完整 Prompt 或 Completion。

## 8. Spring AI Observation 与 Micrometer

### 8.1 框架自动采集能力

Spring AI 1.1.8 文档说明：需要 `spring-boot-starter-actuator` 才能启用对应 Observability；ChatClient 与 ChatModel 分别可以产生 Observation：

- ChatClient Observation：测量 `call()` / `stream()` 操作时间，传播相关 Trace 信息。
- ChatModel Observation：测量模型 `call()` / `stream()` 的执行时间；适用实现还可记录 `gen_ai.client.token.usage`。
- Tool Calling 和 Advisor 也有对应 Observation。

### 8.2 版本与 DeepSeek 特别注意

Spring AI 1.1.8 Observability 页面列出的 ChatModel 自动观测支持厂商名单是 Anthropic、Azure OpenAI、Mistral AI、Ollama、OpenAI、Vertex AI、MiniMax、Moonshot、QianFan、Zhipu AI；没有明确列出 DeepSeek。与此同时，1.1.8 Javadoc 中存在 DeepSeekChatModel 和 DeepSeek 的 Provider 标识。

工程结论：

1. 不要根据“有 DeepSeek Starter”推断 Token Meter 和 Trace 一定自动导出。
2. 先用真实项目依赖构建 `SimpleMeterRegistry` / Actuator metrics 验证是否出现预期 Meter。
3. 如果没有自动 Meter，使用 `ChatResponseMetadata.getUsage()` 做应用层日志或自定义 Counter/Timer。
4. 如果自动 Meter 已经记录同一份 Usage，不要再无差别增加一套重复 Counter，避免统计重复。
5. 不同模型供应商的字段、流式结束事件及 Retry 行为可能不同，必须用 Mock 与有限的集成测试验证。

### 8.3 1.1.8 相关指标名称

Spring AI 文档列出的主要 Meter 名称：

- `gen_ai.client.operation`：ChatModel 调用耗时 Timer。
- `gen_ai.client.token.usage`：Token Usage Counter，按 Token 类型区分 input/output/total。
- ChatClient 操作也有对应的 ChatClient operation Timer。

在 Prometheus 导出时，Micrometer/Spring AI 的点号命名会转换为下划线，并附带单位或类型后缀。文档给出的 ChatModel 例子包括：

- `gen_ai_client_operation_seconds_sum`
- `gen_ai_client_operation_seconds_count`
- `gen_ai_client_operation_seconds_max`
- `gen_ai_client_operation_active_count`
- `gen_ai_client_token_usage_total`

Token Counter 的类型标签包括 `input`、`output`、`total`。具体暴露结果以项目使用的 Registry、导出器和实际 `/actuator/metrics` 或 Prometheus scrape 为准。

### 8.4 低基数与高基数字段

- 低基数字段：provider、受控 model、operation、status 等固定集合字段，可考虑作为 Meter 标签。
- 高基数字段：requestId、traceId、spanId、conversationId、响应 ID、Prompt 正文、Tool 参数等，不应作为常规 Meter 标签。
- Trace 可承载部分高基数关联字段，但也要遵守长度、敏感信息和访问控制要求。

框架已提供的 Observation 能力应优先复用。需要业务专属指标时再增加自定义 Meter，并明确它测量的边界，避免重复计时或重复计算 Token。

## 9. 最小 Mock 实践

### 9.1 依赖基线

在现有 M2 项目的 `pom.xml` 中先核对是否已有对应依赖；不要重复添加 Starter。核心依赖形态如下（实际版本以项目 BOM 和依赖树为准）：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.8</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-deepseek</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

`spring-ai-bom` 用于统一 Spring AI 组件版本；Spring Boot BOM 管理 Boot 与 Micrometer 生态依赖。不要手工猜测 Micrometer 子模块版本。若现有项目已通过父 POM 或 BOM 管理版本，应沿用现有依赖管理，不要再引入第二套版本管理。

### 9.2 配置：只使用环境变量注入密钥

**Spring AI 1.1.8 的版本注意：**`DeepSeekChatProperties` 暴露嵌套的 `getOptions()`，因此此版本的模型配置使用 `spring.ai.deepseek.chat.options.model`。不要把当前未分版本的新版参考页面中的 `spring.ai.deepseek.chat.model` 配置直接复制到 1.1.8；升级 Spring AI 后应重新核对配置属性元数据与 Javadoc。

```yaml
spring:
  application:
    name: m2-spring-ai-demo
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        options:
          model: ${DEEPSEEK_MODEL:deepseek-chat}
    chat:
      client:
        observations:
          log-prompt: false
          log-completion: false
      observations:
        log-prompt: false
        log-completion: false
        include-error-logging: false

app:
  llm:
    provider: deepseek
    model: ${DEEPSEEK_MODEL:deepseek-chat}

logging:
  structured:
    format:
      console: logstash
```

Mock 单元测试不需要真实 `DEEPSEEK_API_KEY`，也不应发起真实网络请求。若运行真实应用，密钥只能通过环境变量或专用密钥管理设施注入；不能提交 `.env`、实际 `application-local.yml`、IDE Run Configuration 导出文件或真实密钥。

### 9.3 Usage 提取器

```java
package com.example.m2.observability;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

public record UsageSnapshot(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String usageSource) {

    public static UsageSnapshot from(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return unknown();
        }

        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return unknown();
        }

        return new UsageSnapshot(
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
                "SPRING_AI_RESPONSE_METADATA");
    }

    public static UsageSnapshot unknown() {
        return new UsageSnapshot(null, null, null, "UNKNOWN");
    }
}
```

### 9.4 构造 Mock ChatResponse

Spring AI 1.1.8 Javadoc 已确认以下构造方式存在：`DefaultUsage(Integer,Integer,Integer)`、`ChatResponseMetadata.builder()`、`Generation(AssistantMessage)`、`ChatResponse(List<Generation>, ChatResponseMetadata)`。

```java
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

ChatResponse mockResponse = new ChatResponse(
        List.of(new Generation(new AssistantMessage("合成回答，不用于日志"))),
        ChatResponseMetadata.builder()
                .id("mock-response-001")
                .model("mock-chat-model")
                .usage(new DefaultUsage(126, 74, 200))
                .build());

UsageSnapshot snapshot = UsageSnapshot.from(mockResponse);
// snapshot = (126, 74, 200, "SPRING_AI_RESPONSE_METADATA")
```

这只构造了内存中的 Mock 响应，不会访问 DeepSeek，也不代表真实服务端调用。

### 9.5 ChatClient 真实 API 路径，但 Mock ChatModel

下面的测试通过 Mockito 模拟 `ChatModel`，再交给 `ChatClient.create(...)`。它验证 ChatClient 的 `.chatResponse()` 路径能保留模拟响应元数据，不会访问外部模型服务。

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ChatClientMetadataTest {

    @Test
    void chatResponseShouldRetainUsageMetadata() {
        ChatResponse expected = new ChatResponse(
                List.of(new Generation(new AssistantMessage("mock answer"))),
                ChatResponseMetadata.builder()
                        .id("mock-response-001")
                        .model("mock-chat-model")
                        .usage(new DefaultUsage(126, 74, 200))
                        .build());

        ChatModel mockModel = mock(ChatModel.class);
        when(mockModel.call(any(Prompt.class))).thenReturn(expected);

        ChatResponse actual = ChatClient.create(mockModel)
                .prompt()
                .user("synthetic question")
                .call()
                .chatResponse();

        assertSame(expected, actual);
        UsageSnapshot usage = UsageSnapshot.from(actual);
        assertEquals(126, usage.inputTokens());
        assertEquals(74, usage.outputTokens());
        assertEquals(200, usage.totalTokens());
    }
}
```

### 9.6 Usage 缺失测试

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class UsageSnapshotTest {

    @Test
    void shouldMarkMissingUsageAsUnknown() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("mock only"))),
                ChatResponseMetadata.builder()
                        .id("mock-response-no-usage")
                        .model("mock-chat-model")
                        .build());

        UsageSnapshot usage = UsageSnapshot.from(response);

        assertEquals("UNKNOWN", usage.usageSource());
        assertNull(usage.inputTokens());
        assertNull(usage.outputTokens());
        assertNull(usage.totalTokens());
    }

    @Test
    void shouldTreatNullResponseAsUnknown() {
        UsageSnapshot usage = UsageSnapshot.from(null);
        assertEquals("UNKNOWN", usage.usageSource());
        assertNull(usage.totalTokens());
    }
}
```

### 9.7 最小调用采集服务的关键实现

以下片段展示计时、Usage 提取、低基数 Timer 和结构化日志的组合。`ChatClient.Builder` 由 Spring AI Starter 自动配置。同步 Servlet 请求中可从 MDC 取 requestId；没有 Tracer 时 traceId/spanId 允许为空。

```java
@Service
public class LlmCallMetricsService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallMetricsService.class);

    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;
    private final String provider;
    private final String configuredModel;

    public LlmCallMetricsService(
            ChatClient.Builder builder,
            MeterRegistry meterRegistry,
            @Value("${app.llm.provider:deepseek}") String provider,
            @Value("${app.llm.model:unknown}") String configuredModel) {
        this.chatClient = builder.build();
        this.meterRegistry = meterRegistry;
        this.provider = provider;
        this.configuredModel = configuredModel;
    }

    public String chat(String userText) {
        String requestId = Objects.toString(MDC.get("requestId"), "unknown");
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");

        long startedNanos = System.nanoTime();
        ChatResponse response = null;
        String status = "SUCCESS";
        String errorCode = null;

        try {
            response = chatClient.prompt()
                    .user(userText)
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                throw new IllegalStateException("Chat model returned an empty response");
            }
            // 正常业务需要返回回答，但禁止将此内容写入日志。
            return response.getResult().getOutput().getText();
        } catch (RuntimeException ex) {
            status = "ERROR";
            errorCode = "MODEL_CALL_FAILED"; // 归一化错误码，不记录 ex.getMessage()
            throw ex;
        } finally {
            long elapsedNanos = System.nanoTime() - startedNanos;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();
            Integer inputTokens = usage == null ? null : usage.getPromptTokens();
            Integer outputTokens = usage == null ? null : usage.getCompletionTokens();
            Integer totalTokens = usage == null ? null : usage.getTotalTokens();
            String actualModel = metadata == null || metadata.getModel() == null
                    ? configuredModel : metadata.getModel();
            String responseId = metadata == null ? null : metadata.getId();
            String usageSource = usage == null ? "UNKNOWN" : "SPRING_AI_RESPONSE_METADATA";

            // 此 Timer 包含 ChatClient 操作边界，不要命名成纯 HTTP Provider 耗时。
            Timer.builder("app.llm.chatclient.operation")
                    .tag("provider", provider)
                    .tag("model", configuredModel) // 使用受控配置值，避免响应值无限扩张基数
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);

            // 仅在没有已验证的框架 Token Meter，或业务明确需要独立命名空间时启用。
            recordToken("input", inputTokens);
            recordToken("output", outputTokens);
            recordToken("total", totalTokens);

            var event = "SUCCESS".equals(status) ? log.atInfo() : log.atWarn();
            String eventName = "SUCCESS".equals(status)
                    ? "llm.call.completed" : "llm.call.failed";
            event.addKeyValue("timestamp", Instant.now().toString())
                    .addKeyValue("event", eventName)
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("traceId", traceId)
                    .addKeyValue("spanId", spanId)
                    .addKeyValue("provider", provider)
                    .addKeyValue("model", actualModel)
                    .addKeyValue("operation", "chat")
                    .addKeyValue("streaming", false)
                    .addKeyValue("durationMs", durationMs)
                    .addKeyValue("inputTokens", inputTokens)
                    .addKeyValue("outputTokens", outputTokens)
                    .addKeyValue("totalTokens", totalTokens)
                    .addKeyValue("usageSource", usageSource)
                    .addKeyValue("providerResponseId", responseId)
                    .addKeyValue("status", status)
                    .addKeyValue("errorCode", errorCode)
                    .addKeyValue("retryCount", null) // 未接入重试计数器时必须保持未知
                    .log("LLM call metadata recorded");
        }
    }

    private void recordToken(String tokenType, Integer count) {
        if (count == null || count < 0) {
            return; // 未知不是 0，不写入 Counter
        }
        Counter.builder("app.llm.token.usage")
                .tag("provider", provider)
                .tag("model", configuredModel)
                .tag("token_type", tokenType) // 仅 input/output/total 三种固定值
                .register(meterRegistry)
                .increment(count);
    }
}
```

所需 imports：`ChatClient`、`ChatResponse`、`ChatResponseMetadata`、`Usage`、`MeterRegistry`、`Timer`、`Counter`、`Logger`、`LoggerFactory`、`MDC`、`Instant`、`TimeUnit`、`Objects`、`@Service`、`@Value`。本示例是同步 ChatClient 计时样例；若应用使用 `stream()`，必须改为订阅到终止信号时记录总时长，并单独记录 TTFT。

进一步注意：

- `provider`、`configuredModel` 来自受控配置；不要允许用户随意传入模型名作为 Metric 标签。
- 业务日志里的 `actualModel` 可用于追踪响应实际模型，但仍应限制字符串长度。
- `retryCount=null` 是有意设计。要得到实际重试次数，应在 RetryTemplate/重试机制边界增加计数，不可根据一次成功响应推断没有发生重试。
- 该 Timer 名为 `app.llm.chatclient.operation`，计时范围是 ChatClient 操作，不是严格的 Provider HTTP 时间。
- 本示例仅展示同步模式且单轮对话；含 Tool Calling / 多轮模型调用的业务操作需要逐次记录模型调用，再按业务 requestId 汇总。

### 9.8 合成样本指标记录

下列数据仅用于演示字段与 JSON 结构，明确标记为合成样本，不代表真实 DeepSeek 响应、真实账单或性能结果：

```json
{
  "sampleType": "synthetic",
  "timestamp": "2026-11-03T09:30:00+08:00",
  "level": "INFO",
  "event": "llm.call.completed",
  "requestId": "req-demo-001",
  "traceId": "trace-demo-001",
  "spanId": "span-demo-001",
  "provider": "mock",
  "model": "mock-chat-model",
  "operation": "chat",
  "streaming": false,
  "durationMs": 842,
  "inputTokens": 126,
  "outputTokens": 74,
  "totalTokens": 200,
  "usageSource": "MOCK_RESPONSE_METADATA",
  "providerResponseId": "mock-response-001",
  "status": "SUCCESS",
  "errorCode": null,
  "retryCount": 0
}
```

此条记录的数字均为合成值。正式练习时可以自行替换 Mock 样本，但必须保留 `sampleType=synthetic` 或 `usageSource=MOCK` 标记。

## 10. 测试与本地验收

### 10.1 必须覆盖的测试

| 测试场景 | 预期结果 |
|---|---|
| Usage 存在 | 正确读取 input/output/total Token |
| Usage 对象缺失 | 字段为 null，`usageSource=UNKNOWN`，不抛 NPE |
| Response 或 Metadata 缺失 | 安全返回 UNKNOWN |
| Mock ChatModel 经 ChatClient 调用 | `.chatResponse()` 保留 Mock Usage 元数据 |
| 模型调用抛异常 | 日志仍包含 requestId、耗时、ERROR 和归一化 errorCode |
| 日志白名单 | 日志 JSON 不含 Prompt、Completion、API Key、Authorization、用户正文 |
| MDC 请求结束 | Filter 执行后 requestId 被恢复/清理，不污染线程池下一个请求 |
| Trace 不可用 | traceId/spanId 允许为空，不生成伪造 Trace |
| 重试次数未知 | retryCount 为空，不误报 0 |

### 10.2 失败路径测试建议

将 `ChatModel` Mock 为抛出 `IllegalStateException`，再通过 `ChatClient.create(mockModel)` 构造 ChatClient，并调用采集服务。使用 `SimpleMeterRegistry` 验证 `app.llm.chatclient.operation` 的 `status=ERROR` Timer 的 count 增加 1；同时通过 Logback `ListAppender<ILoggingEvent>` 捕获日志，检查 key-value 中仍有 requestId、durationMs、status 和 errorCode，且不包含测试 Prompt、异常原始消息或模型正文。测试结束后清理 MDC。不要只断言异常被抛出，而忽略 finally 中的记录逻辑。

### 10.3 运行命令

```bash
java -version
mvn -version
mvn -q -DskipTests=false test
mvn dependency:tree -Dincludes=org.springframework.ai,io.micrometer
```

若启用 Actuator，可在本地受控环境检查：

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
```

如需检查某个指标，再使用 Actuator 实际返回的 meter 名称作为路径参数。不要假设 DeepSeek 自动指标一定存在；若不存在，记录证据并采用应用层自定义 Timer/Counter。

### 10.4 本次执行环境的验证状态

- 本次运行环境可用 JDK：OpenJDK 21.0.12.1。
- 本次环境没有安装 Maven；尝试安装 Maven 超时，且 Maven Central DNS 不可解析，无法下载依赖。
- 因此，本笔记中的 JUnit 测试代码没有在本次执行环境中真实运行，不能声称 `mvn test` 已通过，也不能声称完整 Spring AI 示例已经编译通过。
- Spring AI 1.1.8 API 方法和示例构造器已按官方 Javadoc 核验；仍需在你的 M2 仓库中结合实际 `pom.xml` 执行 Maven 编译与测试。

### 10.5 验收清单

- [ ] 从 `pom.xml` / `dependency:tree` 确认 Spring AI 实际补丁版本和 Micrometer 解析版本。
- [ ] 编译 UsageSnapshot 提取器和 ChatClient Mock 测试。
- [ ] Usage 存在、缺失和空响应测试通过。
- [ ] 证明没有触发真实 DeepSeek API 请求。
- [ ] Filter 测试证明 requestId 能传递到调用日志，并在请求结束后清理 MDC。
- [ ] 构造异常场景，日志仍有 requestId、耗时、状态和归一化错误码。
- [ ] 日志检索结果不包含 Prompt、Completion、API Key、Authorization 和业务正文。
- [ ] 导出/检查实际 Micrometer Meter，确认 DeepSeek 观测情况；没有自动 Meter 时明确采用自定义指标。
- [ ] 记录一条 Mock 样本，明确标记 `synthetic` / `MOCK`。
- [ ] 保存 Maven 测试摘要、Meter 检查结果和脱敏后的 JSON 日志样本作为证据。

## 11. 常见错误与排查

| 现象 | 常见原因 | 处理方式 |
|---|---|---|
| Token 字段一直为 null | Provider 没返回 usage、流式协议未包含 usage、适配器未映射 | 检查原生响应协议与 Spring AI Provider 映射；未知就保留 null |
| 只拿到回答字符串 | 使用了 `.content()` | 需要 Usage 时改用 `.chatResponse()` |
| `getMetadata()` 或 Usage 为空 | Mock 未设置 Metadata，或 Provider 响应缺少字段 | Mock 明确构造 Metadata；生产代码做 null 保护 |
| 总耗时高但模型耗时不高 | RAG 检索、Advisor、排队、序列化或其他业务处理耗时 | 分别记录 HTTP、ChatClient、ChatModel 和检索耗时 |
| 流式耗时特别短 | 只测到了 Flux 创建时间 | 从订阅开始测，直到 complete/error/cancel |
| requestId 偶尔丢失 | 异步线程 / Reactor 线程切换，MDC 没有传播 | 使用 TaskDecorator 或 Micrometer Context Propagation |
| 日志里出现用户输入或模型回答 | SimpleLoggerAdvisor、Prompt/Completion 观察日志或直接打印对象 | 关闭内容日志；改为日志字段白名单 |
| Metric 数量暴涨 | 使用 requestId、traceId、用户 ID 等做 Meter 标签 | 将高基数信息移出 Meter 标签，保留在日志/Trace 中 |
| DeepSeek 自动 Token Meter 不存在 | 该 Provider 的自动观测实现/文档支持范围不明确或未映射 | 检查目标 1.1.8 实际 Meter；按 Usage 元数据自定义采集 |
| 重试次数记录不准 | 只在外层统计业务调用，没统计底层尝试 | 在重试机制处增加真实 attempt 计数；未知时为 null |

## 12. 面试复习问题与参考答案

**Q1：Token、字符数和字节数有什么区别？**  
Token 是模型 Tokenizer 切分后的计量单位，和字符数、单词数、UTF-8 字节数没有固定换算关系。中文、代码、JSON 和不同模型会产生不同切分结果。

**Q2：为什么选择 `ChatResponse` 而不是只取 `content()`？**  
`content()` 返回文本字符串；`ChatResponse` 保留 Generation 和响应 Metadata，可以读取模型名、响应 ID、Usage 等结构化数据。

**Q3：Usage 缺失时为什么不能记成 0？**  
0 表示已确认没有消耗；null/unknown 表示没有拿到可信数据。两者语义不同，混用会造成成本统计偏低。

**Q4：为什么用 `System.nanoTime()` 统计耗时？**  
它用于测量时间间隔，避免系统时钟校准或时间跳变影响耗时计算；`currentTimeMillis()` 更适合记录日历时间。

**Q5：ChatClient 耗时与 ChatModel 耗时有什么区别？**  
ChatClient 可能包含 Advisor、上下文准备等框架操作；ChatModel 耗时更接近模型执行层。应根据监控目标明确计时边界。

**Q6：为什么不能把 requestId 作为 Prometheus 标签？**  
requestId 对每个请求几乎都不同，属于高基数值，会生成大量时间序列，增加内存、存储和查询成本。它适合日志检索，不适合作为普通 Metric 标签。

**Q7：requestId 和 traceId 是否相同？**  
不同。requestId 标识应用业务请求；traceId 标识跨服务调用链；一个 Trace 内可有多个 Span；一个业务处理也可能因异步任务或重试关联多个调用。

**Q8：MDC 为什么要在 finally 清理？**  
Servlet 容器和线程池会复用线程。未清理的 ThreadLocal/MDC 值可能被下一个请求继承，导致日志串号和数据归属错误。

**Q9：流式模型调用如何统计耗时？**  
在订阅时开始计时，首次收到非空内容时计算 TTFT，在 complete/error/cancel 时计算总持续时间；不能在 Flux 返回时就结束计时。

**Q10：为什么不能打印 `ChatResponse.toString()` 或完整异常消息？**  
对象或异常内容可能携带 Prompt、模型回答、工具参数、业务数据、URL/Header 或 Provider 原始响应。应使用白名单结构化字段和归一化错误码。

**Q11：Logs、Metrics、Traces 的区别？**  
Logs 是事件明细；Metrics 是聚合时间序列；Traces 是跨服务的请求调用链。它们互补，不应互相替代。

**Q12：DeepSeek 有 Usage API，是否说明 Spring AI 一定会自动暴露 Token Meter？**  
不说明。Provider 原生 API 有 Usage，不等于特定 Spring AI 补丁版本一定已把该字段接入 Observation/Micrometer。需要检查 1.1.8 的 Provider 映射并运行 MeterRegistry 实测；否则用 `ChatResponseMetadata.getUsage()` 自行采集。

## 13. 参考资料

以下均为官方文档或官方 API/Javadoc；引用按本笔记采用的格式列出。

1. Chat Client API|聊天客户端 API：https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html
2. Usage (Spring AI Parent 1.1.8 API)|Token 用量接口（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/metadata/Usage.html
3. ChatResponseMetadata (Spring AI Parent 1.1.8 API)|聊天响应元数据（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/metadata/ChatResponseMetadata.html
4. ChatResponse (Spring AI Parent 1.1.8 API)|聊天响应对象（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatResponse.html
5. DefaultUsage (Spring AI Parent 1.1.8 API)|默认 Token 用量实现（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/metadata/DefaultUsage.html
6. DeepSeekChatProperties (Spring AI Parent 1.1.8 API)|DeepSeek 聊天配置属性（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/model/deepseek/autoconfigure/DeepSeekChatProperties.html
7. DeepSeekChatOptions (Spring AI Parent 1.1.8 API)|DeepSeek 聊天选项（Spring AI 1.1.8 API）：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/deepseek/DeepSeekChatOptions.html
8. Observability|可观测性：https://docs.spring.io/spring-ai/reference/1.1/observability/index.html
9. Getting Started|开始使用与版本兼容：https://docs.spring.io/spring-ai/reference/1.1/getting-started.html
10. Logging|Spring Boot 日志与结构化日志：https://docs.spring.io/spring-boot/3.5/reference/features/logging.html
11. Tracing|Spring Boot 链路追踪与日志关联：https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html
12. Timers|Micrometer Timer 计时器：https://docs.micrometer.io/micrometer/reference/concepts/timers.html
13. Histograms and Percentiles|直方图与百分位数：https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html
14. Purpose|Micrometer Context Propagation 的用途：https://docs.micrometer.io/context-propagation/reference/purpose.html
15. Examples|Micrometer Context Propagation 示例：https://docs.micrometer.io/context-propagation/reference/usage.html
16. Chat Completions API|DeepSeek 聊天补全 API：https://api-docs.deepseek.com/api/create-chat-completion/
17. System Requirements|Spring Boot 3.5.16 系统要求：https://docs.spring.io/spring-boot/3.5/system-requirements.html
