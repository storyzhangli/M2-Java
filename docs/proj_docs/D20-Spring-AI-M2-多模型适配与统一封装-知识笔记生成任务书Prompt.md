# Spring AI M2 知识笔记生成任务书 Prompt
## 主题：多模型适配——切换不同厂商模型与统一封装

> **任务类型：** 知识笔记生成，不是项目迭代任务书。请直接生成最终 Markdown 知识笔记，不要再次生成 Prompt。

## 一、任务目标与技术基线

围绕 Spring AI 多模型接入与统一调用，讲清楚如何在不让业务代码绑定某一家模型厂商的前提下，实现模型切换、多模型共存、能力差异管理及必要的路由/降级。

默认学习基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.x（优先核验 1.1.8）、Maven 3.9.16。**以实际依赖和 Spring AI 1.1.x 官方文档为准，不得直接套用 Spring AI 2.x API、配置项或示例。** 不得为了本主题擅自升级技术版本。

## 二、内容生成约束

1. 先核验 Spring AI 1.1.x 的 API、Starter、配置属性、自动配置行为和模型支持情况，再写代码；每个关键示例标明适用版本。
2. 以 Spring AI 官方文档为主，厂商官方文档为辅。引用采用“英文原始标题 | 中文标题：[完整 URL](完整 URL)”格式，紧贴对应结论；不得编造 API、属性、模型能力或默认值。
3. 讲清“Spring AI 提供统一抽象”不等于“不同厂商能力完全一致”。必须区分通用接口、通用参数、厂商专属参数和厂商能力差异。
4. 示例应精简、可运行、版本匹配；不需要搭建完整微服务或引入不必要的中间件。
5. 不重复展开 ChatMemory、RAG、Tool Calling、SSE、重试/熔断等其他专题，只说明多模型适配与它们之间必要的接口边界。
6. 不默认扫描或读取整个项目源码。若没有指定仓库，本任务只生成知识笔记与独立最小示例，不声称已在用户项目中验证。
7. 默认使用 Mock/Stub 完成切换、路由和异常测试，不调用真实模型 API。只有用户明确授权后，才可设计真实模型连通性验证；不得自动发起真实请求。
8. API Key 必须通过环境变量或安全密钥管理机制注入。禁止把真实 Key 写入源码、配置文件实际值、日志、Prompt、测试数据、临时文件或 Git；日志不得记录 Authorization、完整请求头或敏感 Prompt。
9. 控制篇幅，围绕本主题必要知识展开；删除重复背景、空泛描述和与主题无关的扩展。

## 三、必须覆盖的核心知识

### 1. 多模型适配的基本概念

- 区分模型厂商（Provider）、模型产品/模型 ID、API 协议、Endpoint、模型参数和 Spring AI 适配实现。
- 区分“同一家厂商切换模型”“不同厂商切换”“多个模型同时注册”“OpenAI-compatible 接口接入不同服务商”四种场景。
- 说明 OpenAI API 兼容通常只代表部分协议/接口兼容，不代表参数、工具调用、结构化输出、流式事件和模型行为完全一致。
- 解释为什么要统一接入：降低业务耦合、支持按场景选择模型、供应商替换、成本/质量权衡和故障切换。

### 2. Spring AI 统一抽象与调用链

必须解释并说明关系、职责及典型使用位置：

- `ChatModel`、`StreamingChatModel`；
- `ChatClient`、`ChatClient.Builder`；
- `Prompt`、Message、`ChatOptions`；
- `ChatResponse`、`Generation`、响应元数据；
- 厂商专属 Options 与通用 `ChatOptions` 的边界。

重点讲清：`ChatClient` 是面向应用调用的流畅 API，底层由具体 `ChatModel` 执行；`ChatModel`/`StreamingChatModel` 是模型接入抽象；响应被映射为 Spring AI 统一响应结构，但厂商特有信息与能力未必能完全标准化。

### 3. 不同厂商接入方式与切换模式

比较并给出适用场景：

1. **单一供应商、配置切换模型 ID**：同一适配器下切换不同模型；
2. **Spring Profile / 外部配置切换供应商**：部署环境选择活动模型，不在业务代码写死厂商；
3. **多个 ChatModel / ChatClient 同时存在**：通过 Bean、`@Qualifier`、必要时 `@Primary` 明确注入和选择；说明 Spring AI 1.1.x 多模型场景中的自动配置与 Builder Bean 冲突处理，属性及做法必须按 1.1.x 文档核验；
4. **OpenAI-compatible Endpoint**：解释通过 OpenAI 适配实现接入兼容服务的条件、配置边界及风险；与厂商原生 Starter/原生适配器比较；
5. **运行时模型路由**：按任务类型、租户策略、成本/延迟策略或模型可用性选择模型。

不得把所有服务商都默认当作 OpenAI-compatible；也不得在未核验 Spring AI 1.1.x 支持的情况下，虚构 DeepSeek 原生 Starter、类名或配置前缀。

### 4. 统一封装设计

给出适度的分层设计，并说明是否需要自定义统一门面：

- 业务层：只表达业务任务，不直接依赖厂商 SDK 或厂商专属 Options；
- 应用层统一门面：例如 `ModelGateway` / `AiModelService`，负责标准请求、响应、模型选择与调用；
- Provider Adapter：将统一请求映射为具体 Spring AI `ChatModel`、`ChatClient` 或厂商实现；
- Model Registry / Router：维护模型标识、适用任务、能力清单和可用状态；
- 配置层：保存模型 ID、Endpoint、超时等非敏感参数；密钥单独注入。

必须说明：

- 统一请求/响应 DTO 的必要字段及设计边界；
- 如何保留 `ChatResponse` 元数据、Token 用量或结束原因等有用信息；
- 如何暴露/声明模型能力，避免把所有模型伪装成能力相同；
- 如何处理厂商专属参数（扩展选项、适配器扩展点或明确拒绝不支持参数）；
- 什么时候直接注入 `ChatClient`/`ChatModel` 已足够，什么时候才值得增加 `ModelGateway`；
- 避免为了“统一”而设计过度复杂的通用框架。

至少提供一张 Mermaid 组件关系图和一段最小 Java 接口/实现示例。示例类名可以自拟，但必须标注为教学示例，不得冒充 Spring AI 内置 API。

### 5. 模型能力差异与兼容性矩阵

至少比较以下能力，并标明“框架接口支持 / 当前适配器支持 / 厂商模型或 API 版本支持”三个层次：

- 同步调用与流式输出；
- Tool / Function Calling；
- JSON Mode、JSON Schema、结构化输出；
- 多模态输入（图片、音频、文件等，按具体模型）；
- 通用参数：temperature、topP、maxTokens 等；
- 厂商专属参数：推理模式、推理强度、特殊响应格式等；
- Token Usage、Finish Reason、响应元数据；
- 上下文窗口、输出限制、速率限制和模型可用区域。

矩阵中的支持状态必须有版本匹配的官方证据；证据不足的项目标注“需实测/未确认”，不要推测为支持。

### 6. 模型路由、备用模型与故障切换

- 说明按任务固定模型、按配置切换、按能力路由、按成本/延迟路由和故障备用模型的区别。
- 定义模型白名单，禁止前端/调用方任意传入 Endpoint、厂商密钥或未授权模型 ID。
- 说明备用模型必须满足当前任务最低能力；结构化输出、工具调用、流式输出等不兼容时不能盲目切换。
- 解释切换模型可能导致输出格式、质量、Token 用量、时延和工具行为变化，需通过契约测试和评估集验证。
- 本主题只讲路由边界，不展开重试、限流、熔断完整实现。

### 7. 最小实践练习（默认 Mock）

设计一个小型 Spring Boot 示例，至少完成：

1. 定义统一模型请求与响应；
2. 建立两个模拟 Provider Adapter（例如 `mock-fast`、`mock-quality`），不访问外网；
3. 通过配置或路由策略选择模型；
4. 业务 Service 只依赖统一门面，不感知具体 Adapter；
5. 返回响应时保留模型标识、耗时/用量占位或元数据字段（无真实用量时明确为空，不伪造）；
6. 使用单元测试验证模型切换、未知模型拒绝、能力不支持处理及 Provider 异常映射。

提供必要的 Maven 依赖、配置示例、核心 Java 代码、测试代码和运行命令。只有在确有教学价值时才增加真实厂商适配器；真实 API 练习作为可选步骤，须明确人工授权且不能把密钥写入文件。

## 四、必须解释的常见误区

至少纠正以下问题：

- 认为 `ChatClient` 就是一个可自动连接任意厂商的万能客户端；
- 认为所有 Provider 都有完全相同的参数和功能；
- 把模型 ID、厂商名、Endpoint 和 Spring Bean 名混为一谈；
- 同时引入多个 Starter 后没有处理 Bean 注入歧义；
- 业务代码中散落 `if (provider == ...)` 和厂商 SDK 类型；
- 将 OpenAI-compatible 等同于百分百兼容；
- 切换备用模型时忽略 Tool Calling、JSON Schema、流式等能力差异；
- 把真实 API Key 写进 YAML、源码或日志。

## 五、最终 Markdown 笔记结构

请按以下结构直接生成知识笔记：

1. 学习目标与核心结论；
2. 多模型适配概念与应用场景；
3. Spring AI 抽象层级与调用链；
4. 厂商接入和四种切换模式；
5. 统一封装架构与最小代码示例；
6. 模型能力/参数兼容性矩阵；
7. 路由、备用模型与切换风险；
8. Mock 最小实践（代码、配置、运行与测试）；
9. 常见误区与排错清单；
10. 核心知识自测题（8～12 题，附简明答案）；
11. 官方参考资料。

## 六、交付与验收标准

- 输出一份完整、可直接保存为 `.md` 的知识笔记，不要输出第二份任务书。
- 代码与配置必须针对 Spring AI 1.1.x；无法确认的 API 或配置明确标注，不得伪造。
- 至少包含 1 张架构 Mermaid 图、1 张能力矩阵、1 个 Mock 切换示例和对应测试。
- 必须区分 Spring AI 通用 API、厂商适配实现和自定义应用封装。
- 关键结论带官方来源链接；如当前官方资料未覆盖，说明证据缺口。
- 不声称已在用户本地环境运行；没有实际执行的内容标注为“示例/待验证”。
- 以读者能够解释模型切换机制、设计统一调用边界，并独立完成一个 Mock 多模型切换练习为验收目标。

## 七、优先官方参考资料

以下链接作为起点，生成时仍需确认页面版本和内容；优先引用 Spring AI 1.1.8 文档，不得误用 2.x 文档：

- Chat Model API | 聊天模型 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html)
- Chat Client API | 聊天客户端 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
- Chat Models Comparison | 聊天模型能力比较：[https://docs.spring.io/spring-ai/reference/1.1/api/chat/comparison.html](https://docs.spring.io/spring-ai/reference/1.1/api/chat/comparison.html)
- OpenAI Chat | OpenAI 聊天模型接入：[https://docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html](https://docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html)
- Anthropic Chat | Anthropic 聊天模型接入：[https://docs.spring.io/spring-ai/reference/1.1/api/chat/anthropic-chat.html](https://docs.spring.io/spring-ai/reference/1.1/api/chat/anthropic-chat.html)
- DeepSeek API Docs | DeepSeek API 官方文档：[https://api-docs.deepseek.com/](https://api-docs.deepseek.com/)

**开始执行：核验版本与官方资料，随后直接生成最终知识笔记。**
