# Spring AI M2 知识笔记：模型抽象、厂商特性与 DeepSeek 接口清单

>
> 资料核验基准日期：2026-11-04
> 说明：DeepSeek 官方模型发现端点 `[https://api.deepseek.com/models](https://api.deepseek.com/models)` 本次解析失败，模型清单基于官方定价页与 List Models API 文档示例整理，实时模型列表请以接口实际返回为准。
> 技术基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.x

## 1. 学习目标与核心结论

### 学习目标

1. 理解 Spring AI 对多厂商大模型的统一抽象分层，明确 `ChatClient`、`ChatModel`、厂商适配器三层的职责边界
2. 厘清模型系列、展示名称、API Model ID、接口协议的对应关系，避免技术选型与代码开发中的混淆
3. 掌握上下文窗口、最大输出 Token、应用预算的层级差异，以及 Tool Calling 的完整执行链路
4. 梳理 DeepSeek 官方当前模型体系、接口协议与能力矩阵，为 Spring AI 接入提供可复核的选型依据

### 核心结论

1. **分层抽象**：Spring AI 通过 `ChatModel` 统一屏蔽厂商协议差异，`ChatClient` 提供上层流畅调用 API，厂商适配器完成协议转换；通用参数可跨厂商移植，特有能力需通过厂商扩展 Options 或自定义请求实现。
2. **能力不对等**：厂商原生 API 能力 ≠ Spring AI 适配器暴露能力。DeepSeek 等 OpenAI 兼容厂商可通过 OpenAI 适配器快速接入，但高阶特性（思考模式精细调节、FIM、Anthropic 协议）需额外适配。
3. **上下文分层**：上下文窗口是输入+输出总 Token 硬限制，`max_output_tokens` 是单次响应输出上限，应用侧需为系统提示、工具定义、历史对话预留 Token 预算。
4. **DeepSeek 模型体系**：截至 2026-11-04，官方公开 V3.2（`deepseek-chat`/`deepseek-reasoner`，128K 上下文）与 V4 系列（`deepseek-flash`/`deepseek-v4-pro`，1M 上下文）两类模型，支持 OpenAI、Anthropic 双协议兼容。
5. **Tool Calling 职责分离**：模型仅生成工具调用指令，实际工具执行由应用侧/Spring AI 完成，模型本身无法直接访问本地资源与外部系统。

## 2. 核心术语：厂商、模型系列、展示名称、Model ID、API 协议

### 模型层级术语

- **模型系列（Model Family）**：同一技术架构的模型集合，如 DeepSeek-V3、DeepSeek-V4，通常共享基础能力与协议兼容性。
- **具体模型版本**：系列下的正式发布版本，如 DeepSeek-V3.2、DeepSeek-V4.1，对应不同的能力、上下文与定价策略。
- **展示名称（Display Name）**：官方控制台、文档中面向用户的可读名称，如 `DeepSeek-V4.1-Flash`，用于模型选择器与产品展示。
- **API Model ID**：接口请求中 `model` 参数的实际取值，如 `deepseek-flash`，是 API 调用的唯一标识，与展示名称不一定一一对应。

### 服务与协议术语

- **厂商（Provider）**：提供大模型 API 服务的主体，如 DeepSeek、OpenAI、Anthropic。
- **接口协议（Protocol）**：API 的请求/响应格式规范，如 OpenAI Chat Completions 协议、Anthropic Messages 协议；同一厂商可同时支持多套协议。
- **API Endpoint**：具体的 HTTP 接口地址，如 `/chat/completions`，是协议的具体承载路径。

### Spring AI 抽象术语

- **厂商适配器（Provider Adapter）**：Spring AI 中对接特定厂商 API 的实现层，负责将通用请求转换为厂商原生请求，并解析响应为通用格式。
- `Model`：顶层模型抽象接口，定义通用调用方法，是所有模型类型的父接口。
- `ChatModel`：聊天模型的同步调用抽象，定义 `call(Prompt)` 等同步方法，返回 `ChatResponse`。
- `StreamingChatModel`：聊天模型的流式调用抽象，定义 `stream(Prompt)` 方法，返回响应事件流。
- `ChatClient`：Spring AI 提供的上层流畅调用入口，封装 `ChatModel`，支持提示词模板、工具绑定、响应处理等高级能力。
- `Prompt`：模型输入的统一封装，包含消息列表与调用参数 `ChatOptions`。
- `ChatResponse`：模型输出的统一封装，包含回复内容、Token 统计、工具调用等信息。
- `ChatOptions`：模型调用参数抽象，包含通用参数与厂商扩展参数。

## 3. Spring AI 模型抽象与调用链架构图

```
graph TD
    subgraph APP ["应用业务层"]
        A["业务代码 / Controller"]
        B["ChatClient 流畅调用入口"]
    end

    subgraph SPRING_AI ["Spring AI 抽象层"]
        C["ChatModel / StreamingChatModel 统一接口"]
        D["ChatOptions 通用参数抽象"]
        E["Prompt / ChatResponse 通用消息模型"]
    end

    subgraph ADAPTER ["厂商适配器层"]
        F["OpenAI 适配器"]
        G["Anthropic 适配器"]
        H["Ollama 适配器"]
        I["其他厂商适配器"]
    end

    subgraph PROVIDER ["厂商 API 服务层"]
        J["OpenAI API"]
        K["DeepSeek API (OpenAI兼容)"]
        L["Anthropic API"]
        M["本地 Ollama 服务"]
        N["其他厂商 API"]
    end

    A --> B
    B --> C
    C --> F
    C --> G
    C --> H
    C --> I
    F --> J
    F --> K
    G --> L
    H --> M
    I --> N

    D --> E
    E --> C
```

### 调用链说明

1. 业务代码通过 `ChatClient` 发起调用，使用 fluent API 配置提示词、参数与工具。
2. `ChatClient` 底层委托 `ChatModel`/`StreamingChatModel` 执行，统一封装为 `Prompt` 与 `ChatOptions`。
3. 厂商适配器接收通用请求，转换为对应厂商的原生 HTTP 请求，处理认证与序列化。
4. 厂商 API 返回响应，适配器将原生响应反序列化为通用 `ChatResponse`。
5. 结果逐层向上返回，最终交付给业务代码。

## 4. 通用 API 与厂商专属参数边界

Spring AI 的 `ChatOptions` 定义了跨厂商通用的参数集合，保证基础调用可移植；厂商专属能力通过扩展 Options 类暴露，无法跨厂商通用。

### 通用可移植参数（所有合规适配器均支持）

- `model`：模型 ID
- `temperature`：采样温度
- `maxTokens`：最大输出 Token 数
- `topP`：核采样参数
- `frequencyPenalty` / `presencePenalty`：频率与存在惩罚
- 基础消息格式（system/user/assistant 角色）

### 厂商专属参数（需使用对应厂商 Options 类）

- DeepSeek/OpenAI 系：`reasoningEffort`、`responseFormat`（JSON Schema）、`seed`、`logprobs`
- Anthropic 系：系统提示更新策略、思考配置、`topK`
- Ollama 系：本地模型加载参数、`numCtx`、`numGpu` 等运行时参数

### 边界注意事项

1. 通用参数仅保证语义近似，不同厂商的数值范围、默认值可能存在差异。
2. 厂商特有功能（如 DeepSeek 思考模式精细调节、FIM 补全）无法通过通用 `ChatOptions` 配置，需强制转换为厂商专属 Options 或自定义请求拦截器。
3. 切换厂商时，仅依赖通用 `ChatOptions` 的代码可无缝切换，使用厂商扩展参数的代码需单独适配。

## 5. 代表性厂商适配能力对比

>
> 标注说明：1=厂商 API 原生支持；2=Spring AI 1.1.x 适配器明确支持；3=需自定义扩展实现；4=官方未明确说明

| 对比维度               | DeepSeek                                                     | OpenAI                                                       | Anthropic                                              | Ollama                                              |
| ---------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------ | --------------------------------------------------- |
| 原生 API 协议          | 1. OpenAI Chat Completions   1. Anthropic Messages   1. Responses API   1. FIM Completion | 1. Chat Completions   1. Responses API   1. Assistants API 等 | 1. Anthropic Messages API                              | 1. OpenAI 兼容接口 + 原生 REST                      |
| 认证方式               | 1. Bearer Token（Header）                                    | 1. Bearer Token（Header）                                    | 1. `x-api-key` Header   1. `anthropic-version` Header  | 1. 无默认认证，可自定义配置                         |
| Spring AI 1.1.x 适配器 | 2. 无独立官方适配器，可通过 OpenAI 适配器配置 base_url 接入  | 2. 官方 `spring-ai-openai-spring-boot-starter` 完整支持      | 2. 官方 `spring-ai-anthropic-spring-boot-starter` 支持 | 2. 官方 `spring-ai-ollama-spring-boot-starter` 支持 |
| 同步调用               | 2. 支持（OpenAI 兼容模式）                                   | 2. 支持                                                      | 2. 支持                                                | 2. 支持                                             |
| 流式调用               | 2. 支持（SSE，OpenAI 兼容模式）                              | 2. 支持（SSE）                                               | 2. 支持（SSE）                                         | 2. 支持（SSE）                                      |
| Tool Calling           | 1. 原生支持（思考/非思考模式）   2. OpenAI 适配器可映射基础工具调用   3. 思考模式下工具调用需自定义参数 | 2. 完整支持，含并行调用、严格 Schema                         | 2. 完整支持，Tool Use/Tool Result 格式                 | 2. 支持，依赖模型本身能力                           |
| JSON 结构化输出        | 1. 原生支持 JSON Output   2. OpenAI 适配器可配置 `response_format` | 2. 支持 JSON 模式与 JSON Schema                              | 2. 支持结构化输出                                      | 2. 支持，依赖模型                                   |
| 输入模态               | 1. `deepseek-flash` 支持文本+图像   1. 其余模型仅文本   3. OpenAI 适配器图像输入需验证 | 2. GPT-4V 等支持图像输入                                     | 2. Claude 3 系列支持图像                               | 2. 支持多模态模型                                   |
| 思考/推理控制          | 1. 原生支持 `reasoning_effort` 三级调节   3. OpenAI 适配器默认未直接暴露，需扩展参数 | 1. o1 系列支持推理模式   2. 适配器可配置                     | 1. 支持扩展思考   2. 适配器可配置                      | 3. 部分本地模型支持，需自定义参数                   |
| 上下文窗口范围         | 1. 128K ~ 1M Token                                           | 1. 128K ~ 2M Token                                           | 1. 200K ~ 2M Token                                     | 1. 取决于本地模型，通常 4K ~ 128K                   |

>
> 补充：DeepSeek 官方支持 Anthropic Messages 协议，但 Spring AI 1.1.x Anthropic 适配器默认指向官方域名，接入 DeepSeek 需自定义 base_url 与 Header 适配。

## 6. 上下文窗口、Token 与输出上限

### 核心概念区分

- **上下文窗口（Context Window）**：模型单次请求可处理的总 Token 上限，包含输入 Token（系统提示、历史对话、用户输入、工具定义、工具结果）与输出 Token 之和，是模型的硬限制。
- **最大输出 Token（max_output_tokens）**：模型单次响应最多可生成的 Token 数量，是上下文窗口的子集，不能超过上下文窗口上限。
- **请求参数 `max_tokens`**：开发者在 API 请求中设置的本次输出上限，不能超过模型的 `max_output_tokens` 限制，用于控制生成长度与成本。
- **应用侧 Token 预算**：业务系统为单次会话预留的 Token 额度，通常小于模型上下文窗口，需预留冗余应对历史累积、工具返回、RAG 检索内容。

### 层级对比表

| 概念层级   | 字段名称示例              | 含义说明                  | 决定方   | 限制类型                      |
| ---------- | ------------------------- | ------------------------- | -------- | ----------------------------- |
| 模型能力层 | `context_window`          | 输入+输出总 Token 上限    | 模型本身 | 硬限制，超出直接报错          |
| 模型能力层 | `max_output_tokens`       | 单次响应输出 Token 上限   | 模型本身 | 硬限制，请求参数不可超过      |
| API 请求层 | `max_tokens`（OpenAI 系） | 本次调用的输出上限        | 调用方   | 软限制，可在模型上限内调整    |
| 应用业务层 | 应用 Token 预算           | 业务侧预留的总 Token 额度 | 业务系统 | 软限制，超出触发裁剪/摘要策略 |

### 关键说明

1. 上下文占用项：系统提示词、多轮对话历史、工具函数定义、工具返回结果、RAG 检索片段、图片 Token（多模态模型）都会计入上下文窗口。
2. 思考模式模型：`deepseek-reasoner` 等推理类模型的思考过程 Token 会计入输出 Token 总量，占用上下文窗口。
3. 超限处理策略：对话历史滑动裁剪、历史摘要压缩、限制 RAG 检索片段数量、降低 `max_tokens`、大窗口任务自动路由到长上下文模型。
4. 切换模型时必须重新评估上下文限制，不可沿用旧模型的预算配置。

## 7. Tool Calling 原理、Spring AI 执行链路与时序图

### 核心原理

Tool Calling（工具调用）是模型的一种能力：模型根据用户问题判断是否需要调用外部工具，并生成符合格式的工具调用指令；**工具的实际执行由应用侧代码完成，模型本身无法直接访问数据库、本地文件或网络接口**。执行完成后，应用将工具结果回传给模型，模型基于结果生成最终回答。

### Spring AI 执行链路

1. **工具定义**：开发者通过 `@Tool` 注解或 `FunctionCallback` 定义工具方法，包含名称、描述、参数 Schema。
2. **工具绑定**：通过 `ChatOptions` 或 `ChatClient` 将工具列表绑定到本次调用。
3. **请求转换**：适配器将工具定义转换为对应厂商协议的格式，随请求发送给模型。
4. **指令解析**：模型返回工具调用指令，适配器解析为通用 `ToolCall` 对象。
5. **工具执行**：Spring AI 调度层匹配工具方法，传入参数并执行，获取返回结果。
6. **结果回传**：将工具结果封装为消息，追加到对话历史，再次请求模型。
7. **最终生成**：模型基于工具结果生成自然语言回答，返回给应用。

### Tool Calling 时序图

```
sequenceDiagram
    participant User as 用户
    participant App as 应用业务代码
    participant ChatClient as Spring AI ChatClient
    participant Adapter as 厂商适配器
    participant LLM as 大模型API
    participant Tool as 本地工具实现

    User->>App: 提交业务问题
    App->>ChatClient: 发起聊天请求，绑定工具列表
    ChatClient->>Adapter: 封装通用Prompt与工具定义
    Adapter->>LLM: 转换为厂商原生请求，发送工具Schema
    LLM-->>Adapter: 返回工具调用指令（ToolCall）
    Adapter-->>ChatClient: 解析为通用ToolCall对象
    ChatClient->>Tool: 匹配工具方法，传入参数执行
    Tool-->>ChatClient: 返回工具执行结果
    ChatClient->>Adapter: 封装工具结果为消息，追加到历史
    Adapter->>LLM: 携带工具结果再次请求模型
    LLM-->>Adapter: 返回最终自然语言回答
    Adapter-->>ChatClient: 解析为通用ChatResponse
    ChatClient-->>App: 返回最终回答
    App-->>User: 展示回答结果
```

### 注意事项

1. 多轮工具调用：模型可能连续发起多次工具调用，Spring AI 会自动循环执行，直到模型不再调用工具。
2. 并行工具调用：部分模型支持一次生成多个工具调用指令，Spring AI 可并行执行提升效率。
3. 协议差异：OpenAI 协议与 Anthropic 协议的工具调用消息格式、字段名称、回传规则不同，适配器层负责屏蔽差异。
4. 安全边界：工具执行在应用本地环境，必须做参数校验、权限控制，避免模型注入导致的安全风险。

## 8. DeepSeek 当前模型官方清单及能力矩阵

>
> 数据来源：DeepSeek 官方定价页、List Models API 文档示例
> 说明：当前官方公开 V3.2 与 V4 两类模型体系，并行提供服务；未明确标注的能力请以官方最新文档为准。

### 模型基础信息表

| API Model ID        | 展示名称                          | 模型版本 | 上下文窗口    | 最大输出 Token     | 输入模态    | 输出模态 | 定价页明确     | API 文档示例 | 备注                     |
| ------------------- | --------------------------------- | -------- | ------------- | ------------------ | ----------- | -------- | -------------- | ------------ | ------------------------ |
| `deepseek-chat`     | DeepSeek-V3.2 (Non-thinking Mode) | V3.2     | 128K          | 默认 4K，最大 8K   | text        | text     | 是             | 未列出       | 非思考模式，通用对话场景 |
| `deepseek-reasoner` | DeepSeek-V3.2 (Thinking Mode)     | V3.2     | 128K          | 默认 32K，最大 64K | text        | text     | 是             | 未列出       | 思考模式，推理增强       |
| `deepseek-flash`    | DeepSeek-V4.1-Flash               | V4.1     | 1048576（1M） | 393216（384K）     | text, image | text     | 未在定价页列出 | 是           | 轻量高速，支持多模态     |
| `deepseek-v4-pro`   | DeepSeek-V4-Pro                   | V4       | 1048576（1M） | 393216（384K）     | text        | text     | 未在定价页列出 | 是           | 专业级纯文本模型         |

### 模型能力支持矩阵

| 能力项                             | `deepseek-chat` (V3.2) | `deepseek-reasoner` (V3.2) | `deepseek-flash` (V4.1)                      | `deepseek-v4-pro` (V4)                       |
| ---------------------------------- | ---------------------- | -------------------------- | -------------------------------------------- | -------------------------------------------- |
| 思考模式                           | 不支持                 | 支持，默认开启             | 支持，effort 三级（low/high/max），默认 high | 支持，effort 三级（low/high/max），默认 high |
| Tool Calls                         | 支持                   | 支持                       | 官方未明确说明                               | 官方未明确说明                               |
| JSON Output                        | 支持                   | 支持                       | 官方未明确说明                               | 官方未明确说明                               |
| 图像理解                           | 不支持                 | 不支持                     | 支持                                         | 不支持                                       |
| FIM 补全（Beta）                   | 支持                   | 不支持                     | 官方未明确说明                               | 官方未明确说明                               |
| Chat Prefix Completion（Beta）     | 支持                   | 支持                       | 官方未明确说明                               | 官方未明确说明                               |
| 流式输出                           | 支持                   | 支持                       | 支持（协议兼容）                             | 支持（协议兼容）                             |
| OpenAI 协议兼容                    | 支持                   | 支持                       | 支持                                         | 支持                                         |
| Anthropic 协议兼容                 | 官方未明确说明         | 官方未明确说明             | 支持                                         | 支持                                         |
| Responses API 兼容                 | 官方未明确说明         | 官方未明确说明             | 官方未明确说明                               | 官方未明确说明                               |
| 系统提示更新策略（Anthropic 协议） | 官方未明确说明         | 官方未明确说明             | in-history（最新 system 生效）               | leading-only（仅首个 system 生效）           |

## 9. DeepSeek 官方 API 接口目录及协议差异

### 接口总览表

| API 协议                | HTTP 方法 | 基础 URL                                               | 路径                | 认证方式                                 | 核心用途                             | 流式支持    | 会话状态               | Spring AI 1.1.x 支持状态                | 备注                             |
| ----------------------- | --------- | ------------------------------------------------------ | ------------------- | ---------------------------------------- | ------------------------------------ | ----------- | ---------------------- | --------------------------------------- | -------------------------------- |
| OpenAI Chat Completions | POST      | `[https://api.deepseek.com](https://api.deepseek.com)` | `/chat/completions` | `Authorization: Bearer {key}`            | 通用对话、工具调用、JSON 输出        | 支持（SSE） | 无状态，客户端维护历史 | 支持（通过 OpenAI 适配器配置 base_url） | 主流调用方式，兼容 OpenAI SDK    |
| OpenAI Responses API    | POST      | `[https://api.deepseek.com](https://api.deepseek.com)` | `/responses`        | `Authorization: Bearer {key}`            | 新一代对话接口，内置工具循环、多模态 | 支持        | 无状态，客户端维护历史 | 需自定义调用，适配器未完整适配          | 请求结构与 Chat Completions 不同 |
| Anthropic Messages API  | POST      | 官方 Anthropic 兼容域名                                | `/v1/messages`      | `x-api-key` Header + `anthropic-version` | Anthropic 协议兼容调用               | 支持（SSE） | 无状态，客户端维护历史 | 需自定义 base_url 与 Header 适配        | 工具格式遵循 Anthropic 规范      |
| FIM Completion（Beta）  | POST      | Beta 专属 Base URL                                     | `/completions`      | `Authorization: Bearer {key}`            | 前后缀代码补全                       | 支持        | 无状态                 | 适配器未原生支持，需自定义调用          | 仅部分非思考模式模型支持         |
| Models API              | GET       | `[https://api.deepseek.com](https://api.deepseek.com)` | `/models`           | `Authorization: Bearer {key}`            | 获取可用模型列表与元数据             | 不支持      | 无状态                 | 适配器内置调用，可手动获取              | 仅模型发现，非推理接口           |

### 各协议核心说明

1. **OpenAI Chat Completions**
最成熟的主流接口，兼容绝大多数 OpenAI 生态 SDK 与框架；核心请求字段包括 `model`、`messages`、`max_tokens`、`temperature`、`tools`、`response_format` 等；流式响应通过 `stream: true` 开启，返回 SSE 事件流。Spring AI OpenAI 适配器可直接通过 `spring.ai.openai.base-url` 配置接入。
2. **Responses API**
新一代对话接口，简化了工具调用循环、多模态输入的处理；与 Chat Completions 的核心差异在于请求结构更扁平，内置工具执行调度语义。官方未明确 DeepSeek 完整实现细节，Spring AI 1.1.x 未针对该接口做深度适配。
3. **Anthropic Messages API**
遵循 Anthropic 官方协议规范，工具使用 `tool_use` / `tool_result` 消息格式；不同模型的系统提示更新策略不同：flash 模型支持 `in-history`（最新 system 生效），pro 模型为 `leading-only`（仅首个 system 生效）。需使用独立的 Base URL 与认证 Header，不能与 OpenAI 协议混用。
4. **FIM Completion API**
专门用于代码补全场景，支持前缀（prefix）+后缀（suffix）输入，生成中间补全内容。Beta 阶段，仅部分非思考模式模型支持，Spring AI 无原生适配。
5. **Models API**
模型发现接口，返回所有可用模型的元数据，核心字段包括 `id`、`name`、`context_window`、`max_output_tokens`、`input_modalities`、`effort`、`api_capabilities` 等，可用于动态模型路由与能力校验。

## 10. DeepSeek 接口限制、兼容别名与常见误区

### 1. 模型别名与版本误区

- 误区：认为 `deepseek-chat`、`deepseek-reasoner` 是永久唯一模型 ID
- 说明：上述 ID 对应 V3.2 版本模型，V4 系列已启用新的 ID（`deepseek-flash`、`deepseek-v4-pro`）；旧别名可能长期兼容，但不代表是最新模型。选型请以 `/models` 接口实时返回为准。
- 注意：展示名称与 API ID 不是一一对应，同一展示名称可能对应多个版本 ID。

### 2. 协议混用陷阱

- 误区：同一请求可混用 OpenAI 与 Anthropic 协议的参数
- 说明：两套协议的 Base URL、路径、Header、消息格式、工具字段完全不同，必须分别调用，不能拼接混用。
- 误区：OpenAI 兼容 = 完全支持所有 OpenAI 参数
- 说明：DeepSeek 仅兼容核心字段，部分 OpenAI 独有功能（如 Assistants、Fine-tuning）不支持，高阶参数可能被忽略或报错。

### 3. 上下文与输出限制误区

- 误区：上下文窗口 128K = 可以输入 128K 文本
- 说明：上下文窗口包含输入+输出，必须预留输出 Token 空间；思考模式模型的推理过程也会占用输出 Token。
- 误区：`max_output_tokens` 可以随意设置到最大值
- 说明：输出越长，延迟越高、成本越高，且部分模型有默认输出上限，请求参数不可超过模型硬限制。

### 4. Tool Calling 认知误区

- 误区：模型支持 Tool Calling 就能直接调用本地方法
- 说明：模型仅生成调用指令，实际执行由应用侧完成；Spring AI 提供了工具调度能力，但安全校验、权限控制仍需业务实现。
- 误区：所有模型的工具调用格式一致
- 说明：不同协议的工具定义、消息回传格式不同，思考模式下的工具调用行为可能存在差异。

### 5. Spring AI 适配误区

- 误区：DeepSeek 所有原生能力都能通过 Spring AI OpenAI 适配器直接使用
- 说明：适配器仅映射通用 OpenAI 字段，DeepSeek 特有参数（如 `reasoning_effort` 精细调节、FIM、Anthropic 协议）需自定义扩展或直接调用原生 API。

## 11. 最小 Mock 实践（Java 代码、测试与预期输出）

>
> 说明：使用静态 Mock 数据模拟 DeepSeek `/models` 接口返回，完成模型元数据解析与能力查询，不调用真实 API，不依赖外部服务。

### 1. 模型元数据 DTO 定义（JDK 21 Record）

```
import java.util.List;

/**
 * DeepSeek Models API 响应根结构
 */
public record DeepSeekModelsResponse(
        String object,
        List<DeepSeekModel> data
) {}

/**
 * 单个模型元数据
 */
public record DeepSeekModel(
        String id,
        String object,
        String ownedBy,
        String name,
        Integer contextWindow,
        Integer maxOutputTokens,
        List<String> inputModalities,
        List<String> outputModalities,
        ModelEffort effort,
        ApiCapabilities apiCapabilities
) {
    /**
     * 推理强度配置
     */
    public record ModelEffort(
            List<String> supportedLevels,
            String defaultLevel
    ) {}

    /**
     * API 协议能力
     */
    public record ApiCapabilities(
            AnthropicMessagesCapability anthropicMessages
    ) {
        public record AnthropicMessagesCapability(
                String systemPromptUpdate
        ) {}
    }
}
```

### 2. 模型能力查询服务接口与 Mock 实现

```
import java.util.List;
import java.util.Optional;

public interface ModelCapabilityService {
    /** 根据模型ID查询模型元数据 */
    Optional<DeepSeekModel> getModelById(String modelId);

    /** 查询模型上下文窗口大小 */
    Optional<Integer> getContextWindow(String modelId);

    /** 判断模型是否支持指定输入模态 */
    boolean supportInputModality(String modelId, String modality);

    /** 获取所有支持图像输入的模型 */
    List<DeepSeekModel> listVisionModels();

    /** 判断模型是否支持思考模式 */
    boolean supportThinkingMode(String modelId);
}
```

```
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MockModelCapabilityService implements ModelCapabilityService {

    private final Map<String, DeepSeekModel> modelCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initMockData() {
        // 模拟 /models 接口返回数据
        DeepSeekModel flash = new DeepSeekModel(
                "deepseek-flash",
                "model",
                "deepseek",
                "DeepSeek-V4.1-Flash",
                1048576,
                393216,
                List.of("text", "image"),
                List.of("text"),
                new DeepSeekModel.ModelEffort(List.of("low", "high", "max"), "high"),
                new DeepSeekModel.ApiCapabilities(
                        new DeepSeekModel.ApiCapabilities.AnthropicMessagesCapability("in-history")
                )
        );

        DeepSeekModel pro = new DeepSeekModel(
                "deepseek-v4-pro",
                "model",
                "deepseek",
                "DeepSeek-V4-Pro",
                1048576,
                393216,
                List.of("text"),
                List.of("text"),
                new DeepSeekModel.ModelEffort(List.of("low", "high", "max"), "high"),
                new DeepSeekModel.ApiCapabilities(
                        new DeepSeekModel.ApiCapabilities.AnthropicMessagesCapability("leading-only")
                )
        );

        DeepSeekModel chatV3 = new DeepSeekModel(
                "deepseek-chat",
                "model",
                "deepseek",
                "DeepSeek-V3.2 (Non-thinking Mode)",
                131072,
                8192,
                List.of("text"),
                List.of("text"),
                null,
                null
        );

        modelCache.put(flash.id(), flash);
        modelCache.put(pro.id(), pro);
        modelCache.put(chatV3.id(), chatV3);
    }

    @Override
    public Optional<DeepSeekModel> getModelById(String modelId) {
        return Optional.ofNullable(modelCache.get(modelId));
    }

    @Override
    public Optional<Integer> getContextWindow(String modelId) {
        return getModelById(modelId).map(DeepSeekModel::contextWindow);
    }

    @Override
    public boolean supportInputModality(String modelId, String modality) {
        return getModelById(modelId)
                .map(model -> model.inputModalities().contains(modality))
                .orElse(false);
    }

    @Override
    public List<DeepSeekModel> listVisionModels() {
        return modelCache.values().stream()
                .filter(model -> model.inputModalities().contains("image"))
                .toList();
    }

    @Override
    public boolean supportThinkingMode(String modelId) {
        return getModelById(modelId)
                .map(model -> model.effort() != null || "deepseek-reasoner".equals(modelId))
                .orElse(false);
    }
}
```

### 3. 统一抽象与厂商参数边界演示

```
import org.springframework.ai.chat.ChatModel;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Mock 通用 ChatModel 实现，演示统一抽象
 */
@Component("mockGeneralChatModel")
public class MockGeneralChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String content = "Mock通用回复：收到消息 - " + prompt.getContents();
        return ChatResponse.builder()
                .withGenerations(List.of(new org.springframework.ai.chat.Generation(content)))
                .build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .temperature(0.7)
                .maxTokens(1024)
                .build();
    }
}
```

```
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Mock 厂商专属 ChatModel，演示扩展参数边界
 */
@Component("mockDeepSeekChatModel")
public class MockDeepSeekChatModel {

    /**
     * 演示：使用厂商专属 Options 配置特有参数
     * 通用 ChatOptions 无法直接传递 reasoning_effort
     */
    public String callWithReasoning(String message, String reasoningEffort) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("deepseek-reasoner")
                .withInternalParameter("reasoning_effort", reasoningEffort)
                .build();
        
        return "Mock DeepSeek 思考模式回复（强度：%s）：%s".formatted(reasoningEffort, message);
    }
}
```

### 4. 单元测试与验收条件

```
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ModelCapabilityServiceTest {

    @Autowired
    private ModelCapabilityService modelCapabilityService;

    @Test
    void testGetModelById_success() {
        var model = modelCapabilityService.getModelById("deepseek-flash");
        assertTrue(model.isPresent());
        assertEquals("DeepSeek-V4.1-Flash", model.get().name());
        assertEquals(1048576, model.get().contextWindow());
    }

    @Test
    void testGetModelById_unknownId_returnEmpty() {
        var model = modelCapabilityService.getModelById("unknown-model-v99");
        assertTrue(model.isEmpty());
    }

    @Test
    void testSupportImageModality() {
        assertTrue(modelCapabilityService.supportInputModality("deepseek-flash", "image"));
        assertFalse(modelCapabilityService.supportInputModality("deepseek-v4-pro", "image"));
    }

    @Test
    void testThinkingModeSupport() {
        assertTrue(modelCapabilityService.supportThinkingMode("deepseek-flash"));
        assertFalse(modelCapabilityService.supportThinkingMode("deepseek-chat"));
    }

    @Test
    void testListVisionModels() {
        var visionModels = modelCapabilityService.listVisionModels();
        assertEquals(1, visionModels.size());
        assertEquals("deepseek-flash", visionModels.get(0).id());
    }
}
```

### 预期输出与验收

- 所有单元测试通过，Mock 数据稳定可复现
- 通用 `ChatModel` 仅依赖通用接口，不耦合厂商实现
- 厂商专属参数必须通过扩展 Options 传递，无法通过通用抽象直接设置
- 未知模型 ID、缺失字段场景返回空或默认值，不抛出异常
- 全程无真实 API 调用，无 API Key 等敏感信息

## 12. 版本兼容性与选型检查清单

- 确认 Spring AI 版本与 Spring Boot 版本兼容矩阵：Spring AI 1.1.x 对应 Spring Boot 3.2+，JDK 17+
- 确认目标厂商是否有官方适配器，无官方适配器时评估 OpenAI 兼容方案可行性
- 核对目标模型的 API Model ID 是否为当前官方推荐，避免使用已弃用别名
- 确认所需能力（工具调用、JSON输出、多模态、思考模式）在对应协议下原生支持
- 确认所需能力在 Spring AI 对应版本适配器中已暴露，未暴露的能力是否有自定义实现方案
- 评估上下文窗口是否满足业务峰值需求，预留 20%~30% 冗余预算
- 确认输出 Token 上限是否满足业务生成长度要求
- 核对计费规则：输入/输出 Token 单价、缓存计费策略、最低计费单位
- 评估协议兼容性：优先选择主流量协议，避免 Beta 接口用于核心生产链路
- 验证流式调用、工具调用、结构化输出的实际行为，不依赖文档描述
- 确认错误码、限流规则、重试策略与现有容错体系适配
- 多厂商路由场景，统一抽象层仅使用通用参数，厂商特有能力做降级处理

## 13. 面试/自测问题（附简要参考答案）

1. **Spring AI 中 ChatClient 和 ChatModel 的区别是什么？各自职责是什么？**
答：`ChatModel` 是底层模型调用的统一抽象接口，定义了同步/流式调用的基础方法，直接对接厂商适配器；`ChatClient` 是上层封装的流畅调用入口，提供提示词模板、工具绑定、响应处理等高级能力，底层委托 `ChatModel` 执行。简单说，`ChatModel` 负责“怎么调用模型”，`ChatClient` 负责“怎么方便地调用模型”。
2. **什么是 API Model ID？它和展示名称有什么区别？**
答：API Model ID 是接口请求中 `model` 参数的实际取值，是调用的唯一标识；展示名称是官方面向用户的可读名称，用于控制台、文档展示。两者不一定一一对应，同一展示名称可能对应多个版本的 Model ID，选型与代码中必须使用准确的 Model ID。
3. **上下文窗口和最大输出 Token 有什么区别？**
答：上下文窗口是模型单次请求可处理的输入+输出总 Token 上限，是模型的硬限制；最大输出 Token 是单次响应最多可生成的 Token 数量，是上下文窗口的子集。应用开发中不能将上下文窗口等同于可输入文本长度，必须预留输出 Token 空间。
4. **Tool Calling 中，模型和应用各自的职责是什么？**
答：模型负责判断是否需要调用工具、生成符合格式的工具调用指令；应用负责执行实际的工具方法、校验参数、处理异常，并将执行结果回传给模型。模型本身无法直接访问本地资源，所有工具执行都在应用侧完成。
5. **厂商原生支持某功能，是否代表 Spring AI 适配器一定支持？为什么？**
答：不一定。Spring AI 适配器仅实现了通用能力与部分主流厂商特性，厂商新增的高阶功能（如思考强度调节、FIM补全、新协议）可能未及时适配。这种情况下要么等待适配器版本更新，要么通过自定义请求拦截、直接调用原生 API 实现。
6. **DeepSeek 支持哪些主流 API 协议？接入 Spring AI 常用的是哪种？**
答：DeepSeek 支持 OpenAI Chat Completions、Anthropic Messages、Responses API 等协议。接入 Spring AI 最常用的是 OpenAI 兼容协议，通过配置 OpenAI 适配器的 `base_url` 即可快速接入，无需额外开发。
7. **对话历史、工具定义、工具结果会占用上下文窗口吗？**
答：都会占用。系统提示词、多轮对话历史、工具函数的 Schema 定义、工具返回的结果、RAG 检索片段，所有发送给模型的内容都会计入输入 Token，占用上下文窗口。因此长对话场景需要做历史裁剪或摘要。
8. **什么是厂商适配器？它在 Spring AI 架构中的作用是什么？**
答：厂商适配器是 Spring AI 对接具体厂商 API 的实现层，作用是将通用的 `ChatModel` 请求、`Prompt`、`ChatOptions` 转换为对应厂商的原生 HTTP 请求，并将厂商响应反序列化为通用 `ChatResponse`，从而屏蔽不同厂商的协议差异，让上层业务代码可以统一抽象。
9. **通用 ChatOptions 和厂商专属 Options 有什么关系？切换厂商时要注意什么？**
答：通用 `ChatOptions` 定义了跨厂商通用的参数（温度、最大Token等），保证基础调用可移植；厂商专属 Options 继承通用 Options，扩展厂商特有参数。切换厂商时，仅依赖通用 `ChatOptions` 的代码可无缝切换，使用了厂商扩展参数的代码需要单独适配修改。
10. **DeepSeek 的 deepseek-reasoner 和 deepseek-chat 核心区别是什么？**
答：两者同属 V3.2 系列，核心区别在于是否开启思考模式：`deepseek-chat` 是非思考模式，响应速度快，适合通用对话；`deepseek-reasoner` 是思考模式，具备更强的推理能力，默认输出更长，推理过程 Token 会计入输出总量。
11. **模型超出上下文窗口限制时，常见的处理策略有哪些？**
答：常见策略包括：滑动窗口裁剪最早的对话历史、对历史对话做摘要压缩、限制 RAG 检索片段的数量与长度、降低 `max_tokens` 输出上限、将长任务拆分多次调用、自动路由到更大上下文窗口的模型。
12. **为什么不能直接认为“OpenAI 兼容”就是完全兼容？**
答：OpenAI 兼容通常仅指核心 Chat Completions 接口的基础字段兼容，厂商可能不支持 Assistants API、Fine-tuning、Logprobs 等进阶功能，部分参数的语义、默认值、取值范围也可能存在差异，高阶特性不能直接默认支持，需要逐一验证。

## 14. 官方资料索引

| 英文标题                                | 中文标题                      | 完整 URL                                                     | 用途                                        | 核验日期   |
| --------------------------------------- | ----------------------------- | ------------------------------------------------------------ | ------------------------------------------- | ---------- |
| Models & Pricing                        | 模型与定价                    | [https://api-docs.deepseek.com/quick_start/pricing/](https://api-docs.deepseek.com/quick_start/pricing/) | 查询 V3.2 模型参数、能力与定价              | 2026-11-04 |
| Lists Models                            | 模型列表接口                  | [https://api-docs.deepseek.com/api/list-models/](https://api-docs.deepseek.com/api/list-models/) | 查询 Models API 定义、返回字段、V4 模型示例 | 2026-11-04 |
| Models Endpoint                         | 模型发现端点                  | [https://api.deepseek.com/models](https://api.deepseek.com/models) | 实时获取可用模型清单（本次解析失败）        | 2026-11-04 |
| Spring AI Reference Documentation 1.1.x | Spring AI 1.1.x 官方参考文档  | [https://docs.spring.io/spring-ai/reference/1.1.x/](https://docs.spring.io/spring-ai/reference/1.1.x/) | Spring AI 抽象、适配器、API 官方说明        | 2026-11-04 |
| Spring AI OpenAI Chat Model             | Spring AI OpenAI 聊天模型文档 | [https://docs.spring.io/spring-ai/reference/1.1.x/api/ai/openai-chat.html](https://docs.spring.io/spring-ai/reference/1.1.x/api/ai/openai-chat.html) | OpenAI 适配器参数、能力说明                 | 2026-11-04 |

需要我补充 Spring AI 1.1.x 接入 DeepSeek 的完整配置示例和常见踩坑点吗？