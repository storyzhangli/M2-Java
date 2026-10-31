# Spring AI 知识笔记生成任务书 Prompt

## 主题：结构化输出、JSON / POJO 映射、字段约束、枚举与反序列化失败

> **任务类型：知识学习与工程实践笔记生成**  
> **目标：** 生成一份适合 Java 后端开发者系统学习、能够结合 Spring AI 项目实操、可用于技术面试和项目排障的完整 Markdown 知识笔记。  
> **默认技术基线：** JDK 21、Spring Boot 3.5.16、Spring AI 1.1.x（优先核验 1.1.8）、Maven 3.9.16。若当前项目或用户明确提供的版本不同，以真实项目为准，并逐项说明差异。

---

# 一、任务目标

请围绕以下主题，生成一份**完整、准确、由浅入深、能运行验证、符合指定版本**的 Spring AI 知识笔记：

**Spring AI 结构化输出：学习结构化输出、JSON / POJO 映射、字段约束、枚举和反序列化失败。**

这不是简单的 API 摘要，也不是只讲 JSON 基础的文章。必须把模型输出到 Java 业务系统之间的完整数据链路讲清楚：

```text
用户需求 / 业务输入
    ↓
Prompt 约束 + 输出类型定义 / JSON Schema
    ↓
Spring AI ChatClient / ChatModel 调用
    ↓
LLM 返回文本（通常是 JSON 文本，但不保证严格遵守）
    ↓
StructuredOutputConverter / BeanOutputConverter
    ↓
Jackson JSON 解析与 Java 类型反序列化
    ↓
Java DTO / Record / POJO
    ↓
Bean Validation 字段约束
    ↓
业务语义校验 / 权限校验 / 状态校验
    ↓
业务服务处理、持久化或后续工作流
```

必须重点说明：**JSON 语法正确、JSON Schema 结构匹配、Jackson 能映射为 POJO、Bean Validation 校验通过、业务语义有效，是不同层次的质量要求，不能混为一谈。**

## 1.1 学习者背景

- 具备约 4 年 Java 后端开发经验，正在恢复工程能力并转型 Java AI 工程师。
- 当前学习重点是 Spring AI 应用开发，不默认具备机器学习或模型训练背景。
- 已熟悉 Java、Spring Boot、Maven、HTTP、REST API、MySQL、Redis 等常规后端技术。
- 需要从“理解原理”推进到“能独立实现、测试、排障和解释企业级代码”。
- 默认以 Java 21、Spring Boot 3.5.16、Spring AI 1.1.x 为学习基线；Spring AI 的具体小版本必须从项目 `pom.xml`、依赖树或用户给定信息确认。

## 1.2 最终能力目标

完成笔记后，学习者应能够：

1. 解释为什么大模型返回的 JSON 仍然只是文本，以及结构化输出解决了什么问题。
2. 使用 Spring AI 将模型输出转换为 Java Record、POJO、List、Map 和泛型类型。
3. 理解 `ChatClient.entity(...)`、`responseEntity(...)`、`StructuredOutputConverter`、`BeanOutputConverter` 的职责和适用边界。
4. 正确设计结构化输出 DTO、字段类型、必填/可选字段、空值策略和枚举值。
5. 区分 Prompt 约束、JSON Schema 约束、模型原生结构化输出、Jackson 反序列化与 Bean Validation。
6. 对模型输出执行字段级校验和业务语义校验，避免未经验证的数据进入核心业务流程。
7. 识别并定位 JSON 格式错误、字段缺失、类型不匹配、未知枚举、日期格式错误、空值问题和嵌套对象映射失败。
8. 设计可控的重试、修复、降级、错误分类、日志和测试机制。
9. 将上述能力应用到制造业设备故障摘要、工单分类、故障等级识别等 AI 应用场景。

---

# 二、资料检索与技术版本核验规则

## 2.1 先核验版本，不得直接套用最新版 API

在生成笔记前，优先执行以下工作：

1. 检查当前对话是否提供了项目目录、`pom.xml`、依赖清单或已有技术文档。
2. 如果存在可访问项目，检查 `pom.xml` 中 Spring Boot、Spring AI BOM / Starter、Jackson、Validation Provider 等版本；必要时使用 Maven 依赖树确认最终生效版本。
3. 如果无法访问项目，则按本任务默认基线讲解，并明确标注“示例基线”，不得声称已扫描项目。
4. 优先使用 Spring AI 1.1.8 对应的官方参考文档和 JavaDoc。涉及 1.1.x 小版本差异时，应明确列出。
5. 不得把 Spring AI 2.x 的 API 或配置方式当作 Spring AI 1.1.x 示例使用。
6. 如果发现官方文档中的功能在 1.1.8 与 2.x 之间存在差异，必须单独设置“版本差异提醒”，给出版本号和对应官方链接。
7. 代码示例必须与选定版本一致；无法验证的 API 不得写成确定可用的代码。

## 2.2 必须优先查阅的官方资料

引用应尽量采用与 Spring AI 1.1.x 对应的官方页面。至少核验以下资料：

- Chat Client API：`ChatClient`、`entity()`、`responseEntity()`、`ParameterizedTypeReference`、同步与流式调用。
- Spring AI 1.1.x JavaDoc：`BeanOutputConverter`、`StructuredOutputConverter`、`FormatProvider`、`AdvisorParams`。
- Spring Framework Bean Validation：`Validator`、`LocalValidatorFactoryBean`、约束注解和校验流程。
- Jackson 官方文档：反序列化配置、枚举处理、未知字段、空值、日期时间格式和类型转换。
- Jakarta Bean Validation 3.0：标准约束注解和校验语义。

下列官方链接可作为起点，必须核对页面内容和版本后再引用：

1. Spring AI 1.1 Chat Client API：  
   https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html
2. Spring AI 1.1.x BeanOutputConverter JavaDoc：  
   https://docs.spring.io/spring-ai/docs/1.1.x/api/org/springframework/ai/converter/BeanOutputConverter.html
3. Spring AI 1.1.x API Index：  
   https://docs.spring.io/spring-ai/docs/1.1.x/api/index-all.html
4. Spring Framework Java Bean Validation：  
   https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html
5. Spring Framework Validation、Data Binding and Type Conversion：  
   https://docs.spring.io/spring-framework/reference/core/validation.html
6. Jakarta Bean Validation 3.0 Specification：  
   https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html
7. Jakarta `@NotBlank` API：  
   https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/notblank
8. Jackson Databind Deserialization Features：  
   https://github.com/FasterXML/jackson-databind/wiki/Deserialization-Features

**引用格式固定为：**  
`English Original Title | 准确中文翻译标题：[完整 URL](完整 URL)`

引用紧贴其支持的技术结论。不得只列网站首页，不得使用无法打开或与论断无关的链接，不得把社区文章包装成官方文档。

## 2.3 版本差异特别约束

- Spring AI 1.1.x 的 `ChatClient` 结构化映射能力，应以 1.1.x 的 `entity(Class)`、`entity(ParameterizedTypeReference)`、`entity(StructuredOutputConverter)`、`responseEntity(...)` 等实际 API 为依据。
- Spring AI 1.1.x 原生结构化输出相关能力，应核对 `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT` 的真实定义、调用方式和底层模型支持情况。
- 不得把 Spring AI 2.x 参考文档中的 `EntityParamSpec.validateSchema()`、`useProviderStructuredOutput()` 等 API，未经 1.1.x JavaDoc 确认就写进 1.1.x 代码示例。若作为升级对比提及，必须明确标注为 2.x 版本能力，并说明不能直接用于 1.1.x。
- 不得宣称所有模型、所有服务提供商、所有 JSON Schema 特性都支持原生结构化输出。必须区分 Spring AI 框架能力与底层模型 / Provider 能力。
- `@NotBlank`、`@NotNull`、`@Size` 等 Bean Validation 注解，不得未经验证就宣称会自动转化为模型可执行的 JSON Schema 限制。必须解释：Schema 生成与 Java 对象创建后的 Bean Validation 是不同环节；对于所用版本，需通过生成的 Schema 或真实测试验证注解是否被纳入。

---

# 三、知识笔记必须覆盖的核心知识

下列模块不得遗漏。可补充必要知识，但不得改变核心范围或用无关概念扩展篇幅。

## 模块 1：结构化输出基础与完整数据链路

必须讲清楚：

1. 大模型本质上返回文本；让模型“请返回 JSON”并不等于获得 Java 对象，也不保证响应一定是合法 JSON。
2. 什么是 Structured Output（结构化输出），解决什么问题，适用于哪些业务场景。
3. 结构化输出与普通文本生成、JSON Mode、JSON Schema、Tool Calling / Function Calling 的关系和区别。
4. 结构化输出的完整处理链路：定义目标类型、生成格式指令或 Schema、模型生成、解析、反序列化、字段校验、业务校验。
5. 必须分别解释以下五层，不得混用：
   - JSON 语法合法性：文本是否符合 JSON 语法。
   - JSON Schema 结构约束：字段、类型、必填项、枚举、数组结构等是否符合 Schema。
   - Java 类型映射：Jackson 是否能把 JSON 转换为目标 Java 类型。
   - Bean Validation：对象字段是否满足声明式约束。
   - 业务语义校验：值是否符合真实业务规则、权限和状态约束。
6. 为什么结构化输出仍可能失败：模型漂移、Markdown 包裹、解释文本、字段遗漏、多余字段、值类型不匹配、截断、非法枚举等。
7. 何时应该选用结构化输出，何时直接返回文本更合适。

必须给出一个架构流程图（Mermaid）和一张“各校验层对照表”。

## 模块 2：Spring AI 结构化输出核心 API

必须讲解并给出版本匹配代码：

1. `ChatClient` 基本创建与调用方式。
2. `.call().content()` 与 `.call().entity(...)` 的区别。
3. `entity(Class<T>)`：转换为单个明确 Java 类型。
4. `entity(ParameterizedTypeReference<T>)`：转换为泛型类型，例如 `List<FaultItem>`、`Map<String, Object>` 或嵌套泛型。
5. `responseEntity(...)`：同时获取结构化实体与原始 `ChatResponse` / 响应元数据的用途；确认 1.1.x 实际 API 后给出代码。
6. `StructuredOutputConverter<T>` 与 `FormatProvider` 的职责：格式说明生成与字符串转换。
7. `BeanOutputConverter<T>`：根据目标 Java 类型生成格式要求 / JSON Schema，并解析 LLM 文本为 Java 对象。
8. `MapOutputConverter`、`ListOutputConverter` 的用途、局限和选择依据；不得将 `ListOutputConverter` 的文本逗号分隔列表能力错误描述为任意 JSON 数组对象映射。
9. 自定义 `StructuredOutputConverter` 的适用条件、实现步骤、异常处理、测试方式。
10. `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT`：解释其作用、模型支持前提、API 配置和失败回退策略；必须验证 1.1.x 的真实类名和导入路径。
11. `.entity(...)` 需要完整响应后才能映射的特性，与 `.stream()` 流式返回 `Flux<String>` 的差异；如果需要流式结构化数据，应说明聚合后解析或采用增量 JSON 协议的复杂度，不得声称 1.1.x 可以直接 `.stream().entity(...)`。
12. 区分 Provider 原生约束与 Spring AI Prompt 级格式引导；解释二者均不意味着业务校验可以省略。

至少提供以下类型的可编译示例：

- 单个 POJO / Record。
- `List<DTO>` 泛型映射。
- 嵌套 DTO / 列表字段映射。
- 通过 `BeanOutputConverter` 显式转换原始字符串。
- 获取结构化实体及必要响应元数据。

所有 import、Maven 依赖、方法签名必须与 Spring AI 1.1.x 实际版本一致。代码必须标注文件名 / 所属类 / 所需依赖。

## 模块 3：JSON 与 Java POJO / Record 映射原理

必须覆盖：

1. JSON Object、Array、String、Number、Boolean、Null 与 Java 类型的映射关系。
2. JSON 字段名与 Java 属性名的映射、大小写、命名策略和 `@JsonProperty`。
3. Java Bean（无参构造 + Setter / Getter）与 Java Record（构造器 / 组件访问器）的映射差异。
4. 构造器、Setter、字段访问在反序列化过程中的作用；避免把 Jackson 的所有映射行为错误归结为 Setter。
5. 基本类型与包装类型：`int` / `Integer`、`boolean` / `Boolean` 的缺失和 null 处理差异。
6. 数值类型：`int`、`long`、`BigDecimal`、浮点数精度、整数溢出和类型强制转换风险。
7. 时间类型：`LocalDate`、`LocalDateTime`、`OffsetDateTime`、`Instant` 的格式和时区处理；结合实际 Jackson Java Time 模块和配置说明。
8. 集合与泛型：`List<T>`、`Set<T>`、`Map<K,V>`、嵌套泛型和类型擦除问题；说明为什么需要 `ParameterizedTypeReference`。
9. 嵌套对象、嵌套集合、空集合和 null。
10. 字段缺失、显式 null、空字符串、空数组和默认值之间的语义差异。
11. 未知字段、重复字段、字段别名、命名不一致和类型强制转换。
12. Jackson 常见配置项必须结合版本与项目配置说明，不得随意开启宽松转换来掩盖模型问题。

必须提供一张“JSON 类型 → Java 类型 → 典型问题 → 处理方式”映射表。

## 模块 4：DTO 设计与字段约束

必须覆盖：

1. 为什么要定义专用 AI 输出 DTO，不应直接让模型输出并写入数据库实体或领域聚合根。
2. Record 与普通 POJO 的适用场景、不可变性、构造器和校验方式。
3. 字段设计原则：命名稳定、类型明确、职责单一、字段数量可控、避免无意义嵌套。
4. 必填字段、可选字段、nullable 字段、字段默认值的设计。
5. 字符串约束：`@NotBlank`、`@NotEmpty`、`@Size`、`@Pattern`。
6. 数值约束：`@NotNull`、`@Min`、`@Max`、`@DecimalMin`、`@DecimalMax`、`@Digits`。
7. 集合约束：集合本身的 `@NotEmpty` / `@Size` 与集合元素上的约束（例如 `List<@NotBlank String>`）的区别。
8. 嵌套对象约束：`@Valid` 的级联验证意义。
9. 自定义约束和跨字段业务校验的适用条件。
10. `jakarta.validation` 与旧版 `javax.validation` 的包名区别；Spring Boot 3.x 应核对使用 Jakarta 命名空间，不可混用旧包。
11. `spring-boot-starter-validation` 的作用、Validation Provider 的存在与 `Validator` 的注入/使用。
12. 明确区分 DTO 字段约束、JSON Schema 约束和业务层校验；不得认为添加注解就能阻止模型生成非法数据。
13. 结构化结果验证失败后如何返回清晰、可追踪的字段错误；不要只返回“参数不合法”。

必须给出：

- 一个适合 AI 结构化输出的 DTO / Record 示例。
- 对应的 Bean Validation 注解说明。
- 使用 `jakarta.validation.Validator` 手动执行 `validate()` 的完整示例。
- 如何把 `ConstraintViolation` 转换为内部校验错误 DTO 的示例。
- 对于字段值通过格式验证但不满足业务规则的情况，给出独立业务校验示例。

## 模块 5：枚举值设计与反序列化

必须覆盖：

1. 为什么 AI 输出适合使用枚举表达有限集合，而不是任意字符串。
2. Java Enum 的 `name()`、`toString()`、自定义业务编码之间的区别。
3. JSON 枚举通常使用字符串值；说明大小写、前后空格、中文标签、别名和未知值的处理策略。
4. Prompt 中明确列出允许值、每个值的业务含义、选择规则和无法判断时的兜底值。
5. JSON Schema 中 `enum` 的表达及是否真正被 Provider 强制执行的版本 / 模型差异。
6. Jackson Enum 映射与常用注解：`@JsonCreator`、`@JsonValue`、必要时 `@JsonProperty`、`@JsonEnumDefaultValue`；必须讲清各注解适用范围和所需配置。
7. 未知枚举值处理的不同策略：严格失败、映射为 UNKNOWN、映射为 null 或自定义解析；比较风险与适用场景。
8. `READ_UNKNOWN_ENUM_VALUES_AS_NULL`、`READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`、`READ_ENUMS_USING_TO_STRING` 等 Jackson 功能，如需展示，必须核验当前 Jackson 版本与真实配置行为。
9. 不要默认使用 Enum 的 `ordinal()` 作为 API / 数据库存储值；解释枚举顺序变化造成的风险。
10. 对外稳定 API 值与内部 Java 枚举名称解耦的设计方式。
11. 对模型产生未知枚举的处理：记录原始值、返回可诊断错误或采用显式 UNKNOWN，不能静默把未知值当作正常业务状态。

必须提供严格模式与宽容模式的枚举映射示例，并说明生产系统默认建议以及为什么。

## 模块 6：反序列化失败与异常诊断

必须覆盖并区分以下失败类型：

### A. 模型输出层

- 返回普通解释文本，而不是 JSON。
- JSON 被 Markdown 代码围栏包裹。
- JSON 前后混入解释性文本。
- JSON 被截断、缺少括号或引号。
- 模型输出字段名与 DTO 不一致。
- 缺字段、多字段、字段值超出枚举范围。

### B. JSON 解析层

- JSON 语法不合法。
- 转义字符错误、引号错误、尾逗号等。
- 根节点类型不符合预期（对象与数组不一致）。

### C. Jackson 映射层

- `InvalidFormatException`：值无法转换为目标类型等典型场景。
- `MismatchedInputException`：JSON 结构与目标类型不匹配等典型场景。
- `UnrecognizedPropertyException`：出现未知属性且配置要求失败时。
- `ValueInstantiationException`：构造对象失败等场景。
- `JsonMappingException` / `JsonProcessingException` 的层级和常见用途。
- 枚举、数字、日期时间、Record 构造器和嵌套属性的映射错误。

### D. Java Bean Validation 层

- 反序列化已经成功，但字段值违反 `@NotBlank`、`@Size`、`@Min` 等约束。
- `@Valid` 嵌套校验未正确触发。
- Validation Provider 缺失或依赖未正确引入。

### E. 业务语义层

- 字段格式正确，但设备编号不存在。
- 枚举值合法，但当前设备状态不允许该操作。
- 日期、数量、等级组合违反业务规则。
- 模型猜测了不存在的业务 ID。

每一种异常都必须使用“错误现象 → 触发原因 → 定位方法 → 修复方式 → 是否允许重试 → 测试用例”的结构说明。

必须解释 Spring AI、Converter、Jackson、Validation 之间的异常边界。异常的实际顶层包装类可能受调用路径和版本影响，必须避免声称所有失败都会抛出同一个固定异常；给出的异常捕获代码要基于真实 API 并保留异常链。

## 模块 7：可靠性设计、重试与降级

必须覆盖：

1. Prompt 约束：仅输出 JSON、字段定义、枚举范围、必填/可选、日期格式、数值单位和未知值规则。
2. Schema / 原生结构化输出：何时可用、Provider 支持限制、成本与可靠性权衡。
3. 应用端反序列化检查与 Bean Validation：不能因为 Prompt 写了规则就取消校验。
4. 失败修复策略：将可安全公开的校验错误反馈给模型，要求纠正格式；说明 1.1.x 下哪些需要应用自行实现，不能凭空调用 2.x API。
5. 有界重试：最大重试次数、总耗时、退避、成本和防止无限循环。
6. 不同失败的处理：
   - 可修复的 JSON 格式错误：可以进行有限重试。
   - Provider 429 / 5xx / 超时：使用独立的传输层重试与退避策略。
   - 认证失败、模型不存在、参数错误：通常不应盲目重试。
   - 业务校验失败：按业务策略拒绝、人工确认或重新提问，不应一律让模型无限修复。
7. 不要对所有异常统一 catch 后重新调用模型；不得将无意义重试包装成“自愈”。
8. 原始模型输出的审计、脱敏、长度限制和敏感字段保护。
9. 结构化 DTO 版本演进和向后兼容：字段新增、字段改名、枚举新增、默认值策略。
10. 输出仍不能通过验证时的降级方式：返回明确失败状态、转人工处理、保留原始诊断信息但不直接进入核心业务。

必须提供一个有最大次数限制的应用层重试流程伪代码或 Java 示例，并说明哪些异常可重试、哪些不可重试。

## 模块 8：测试体系与测试用例

必须覆盖：

1. Converter 单元测试：合法 JSON、代码围栏、前后解释文本、错误 JSON。
2. POJO / Record Jackson 映射测试。
3. 枚举严格匹配、大小写变化、未知值、空值和 null 测试。
4. Bean Validation 测试：字段缺失、空白、越界、集合元素和嵌套对象。
5. 业务语义校验测试。
6. Spring AI ChatClient 集成测试：成功结构化输出、异常输出、模型服务错误。
7. 使用 Mock / Stub 进行确定性测试与真实模型联调的边界；自动化测试不得依赖每次模型生成完全相同的随机文本。
8. 测试报告应该能显示测试数量、失败项、异常原因；不得把“请求成功”当作“结构化数据验收通过”。
9. 需要测试模型提供商原生结构化输出时，单独设计 Provider Integration Test，不得用 Mock 结果伪装为真实 Provider 能力。

必须给出测试矩阵：测试输入、期望结果、覆盖层级、测试方法和验收标准。

---

# 四、必须使用的贯穿案例：制造业设备故障信息结构化提取

使用统一业务案例讲解，避免所有示例互不相关。

业务场景：用户输入一段设备故障描述，AI 将文本提取为结构化故障信息，供后续工单分类与处理流程使用。

示例输入：

```text
设备编号 EQ-10086 在运行 20 分钟后出现异常振动，随后温度升高至 92℃，设备尚未停机。操作员听到间歇性金属摩擦声，暂时无法确认具体损坏部件。请判断故障等级并整理故障摘要。
```

建议结构化输出字段（可以在保证业务合理的前提下调整，但必须说明）：

- `equipmentCode`：设备编号。
- `faultSummary`：故障现象摘要。
- `severity`：故障等级枚举，例如 `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`、`UNKNOWN`。
- `symptoms`：症状列表。
- `temperatureCelsius`：温度，使用明确数值类型及单位约定。
- `equipmentStopped`：设备是否已停机。
- `suspectedParts`：疑似部件列表；必须明确这是模型推测而非已确认事实。
- `confidence`：置信度字段（如保留，必须说明它是模型自评，不能等同于客观概率或事实正确性）。
- `missingInformation`：仍需人工确认的信息。

必须完成以下演示：

1. 定义 `FaultAnalysisResult` DTO / Record 与 `FaultSeverity` 枚举。
2. 设计 Prompt，清楚描述字段、枚举、单位和未知值。
3. 使用 Spring AI 1.1.x 兼容 API 请求结构化结果。
4. 将模型结果映射为 Java 对象。
5. 执行 Bean Validation。
6. 执行业务语义校验（例如设备编号是否存在、温度范围是否合理、严重等级与停机状态是否需要人工复核）。
7. 模拟至少三类失败：非法 JSON、未知枚举、字段违反约束。
8. 展示错误处理和有限修复策略。
9. 展示完整测试用例。

不得把“模型推断的疑似部件”作为确定事实写入数据库；必须说明哪些字段可自动保存，哪些字段应标记为推测、待确认或需要人工审批。

---

# 五、代码质量与工程实践硬性要求

1. Java 示例以 JDK 21 为基线，遵循清晰的命名、合理的包结构和必要注释。
2. Spring Boot 示例使用 3.5.16 基线；Spring AI 使用经核验的 1.1.x 版本。若依赖组合不兼容，必须先指出并修正，不得强行拼接。
3. Maven 依赖必须给出完整 artifact 坐标；如果依赖由 BOM 管理，不得无理由重复指定版本。
4. 使用 `jakarta.validation.*`，不要在 Spring Boot 3.x 示例里误用 `javax.validation.*`。
5. 结构化输出 DTO 与数据库 Entity、领域对象、API Request / Response DTO 的边界必须说明。
6. 不要把模型输出直接信任为业务事实；任何高风险操作必须经过校验、权限和审批流程。
7. 不得为了让示例通过而关闭 Jackson 所有严格检查；每个宽松选项都要说明风险。
8. 不得把空字符串、null、缺失字段、`UNKNOWN` 枚举随意视为同义词。
9. 不得把 JSON Schema 校验、Jackson 反序列化和 Bean Validation 合并成一个步骤。
10. 不得用“加一个 try-catch 就解决”替代错误分类和可观测性设计。
11. 代码不得引用不存在的类、方法、包名或属性；每段核心代码都要标注适用 Spring AI 版本。
12. 若代码依赖具体 Provider 原生 JSON Schema 能力，必须注明模型 / Provider、依赖版本和实测限制；没有证据时应使用通用转换路径。
13. 对无法在当前环境执行的示例，只能标记“示例代码 / 尚未执行”，不得伪造测试通过、构建成功或真实模型调用结果。

---

# 六、笔记输出结构与排版要求

最终知识笔记必须按以下目录组织，不能只输出大纲：

```text
# Spring AI 结构化输出：JSON / POJO 映射、字段约束、枚举与反序列化失败

## 0. 学习目标与知识地图
## 1. 结构化输出基础与完整数据链路
## 2. JSON、JSON Schema、POJO 映射与 Bean Validation 的边界
## 3. Spring AI 1.1.x 结构化输出核心 API
## 4. StructuredOutputConverter 与 BeanOutputConverter
## 5. JSON 到 Java Record / POJO 的映射原理
## 6. DTO 字段设计与约束规则
## 7. 枚举值设计与 Jackson 枚举映射
## 8. 反序列化失败类型与异常诊断
## 9. 结构化输出可靠性、重试与降级
## 10. 制造业设备故障结构化提取完整实战
## 11. 测试体系与测试用例矩阵
## 12. 生产环境注意事项与常见反模式
## 13. 面试重点与费曼口述自测题
## 14. 总结：必须掌握的核心知识清单
## 15. 官方参考资料
```

## 6.1 每个核心知识点的讲解模板

每个重要概念尽量按以下顺序组织：

1. **是什么**：定义和准确边界。
2. **为什么需要**：业务问题和工程价值。
3. **底层原理**：执行链路和关键组件。
4. **核心 API / 配置**：名称、职责、版本和使用条件。
5. **代码示例**：可读、完整、版本正确。
6. **运行结果**：展示成功或失败的预期现象；未实际运行时明确标注。
7. **常见错误**：具体输入、异常或错误行为。
8. **生产实践**：如何设计、监控、测试和治理。
9. **面试表达**：用准确的工程语言总结。
10. **参考资料**：紧贴结论提供官方来源。

## 6.2 排版与代码要求

- 使用简体中文，保留准确的英文技术术语和 API 名称。
- 标题层级清晰，避免大段无结构文字。
- 使用 Markdown 表格比较容易混淆的概念。
- 使用 Mermaid 绘制数据链路、组件关系和异常处理流程。
- 代码使用 Java / XML / YAML / JSON / Bash 等正确语言标记。
- Java 代码尽量给出完整 import、类、方法和必要 Maven 依赖。
- JSON 示例必须是合法 JSON；错误案例必须明确标注为非法 JSON，并解释非法原因。
- JSON Schema 示例必须能看懂字段类型、required、enum、数组和嵌套结构；如未实际执行 Schema 验证，须标注为示意。
- 表格列宽适中，不要把完整长代码塞进表格。
- 不要为了凑篇幅重复同一个定义。

---

# 七、必须提供的对比表

最终笔记至少包含以下对比表：

1. 普通文本输出 vs JSON 文本 vs JSON Schema 约束 vs Java POJO 结构化输出。
2. `content()` vs `entity()` vs `responseEntity()`。
3. `Class<T>` vs `ParameterizedTypeReference<T>`。
4. `BeanOutputConverter` vs `MapOutputConverter` vs `ListOutputConverter` vs 自定义 Converter。
5. Prompt 格式约束 vs Provider 原生结构化输出。
6. JSON 语法校验 vs Schema 结构校验 vs Jackson 映射 vs Bean Validation vs 业务校验。
7. Java Bean vs Record。
8. 缺失字段 vs 显式 null vs 空字符串 vs 默认值。
9. 枚举严格失败 vs UNKNOWN 默认值 vs 映射 null。
10. 可重试错误 vs 不可重试错误。

---

# 八、验收标准：输出内容必须达到的质量门槛

完成后必须逐项自检并在文档末尾附上验收清单。

| 验收项 | 通过标准 |
|---|---|
| 版本正确性 | Spring AI 示例明确以 1.1.x 为主，API 与 JavaDoc 匹配；未混入 2.x API |
| 知识覆盖 | 第三章所有 8 个模块均完整覆盖 |
| 概念边界 | 明确区分 JSON、Schema、POJO 映射、Bean Validation、业务校验 |
| API 讲解 | 核心 API 有职责、调用方式、版本、适用条件和代码 |
| JSON 映射 | 覆盖 Bean、Record、泛型、嵌套、null、日期和数值 |
| 字段约束 | 覆盖常用注解、级联验证、Provider 与 Validator 实际验证方式 |
| 枚举 | 覆盖严格映射、未知值策略、Jackson 注解与配置风险 |
| 异常诊断 | 覆盖模型输出、JSON 解析、Jackson、Validation 和业务语义层 |
| 可靠性 | 有有限重试、退避、不可重试条件、降级与审计方案 |
| 实战案例 | 完成制造业故障结构化提取全流程，而非只有片段代码 |
| 测试 | 有单测、映射测试、校验测试、集成测试和测试矩阵 |
| 引用 | 关键技术结论均有可追溯官方链接，标题遵循指定格式 |
| 真实性 | 不伪造执行结果；未运行的代码清楚标记为未验证 |
| 可学习性 | 初学者能循序理解，有知识地图、面试总结和自测题 |

如果任何验收项不满足，必须先补全再交付。不得用“内容已覆盖”这种无证据结论代替逐项检查。

---

# 九、输出文件与交付规则

1. 最终产物是一份独立、完整的 Markdown 知识笔记，不是再次输出一份任务书，也不是只回复聊天文字。
2. 建议文件名：  
   `Spring-AI-结构化输出-JSON-POJO映射-字段约束-枚举与反序列化失败.md`
3. 如果当前环境能访问用户项目或知识笔记目录，优先沿用已有目录结构和命名规范；不得擅自覆盖同名文件。
4. 如果无法确定目标目录，先在当前可访问工作目录中生成该 Markdown 文件，并在最终回复说明实际保存位置；不要编造用户本地 Windows 路径。
5. 若环境支持文件生成，必须实际创建文件并确认文件存在，再提供可访问的下载链接。
6. 最终回复简要说明：文件位置、覆盖的核心模块、使用的 Spring AI 版本基线、哪些代码经过实际验证、哪些仅为示例。
7. 不得声称已经读取项目、执行 Maven、连接模型或运行测试，除非确实执行并有证据。

---

# 十、执行指令

现在开始执行本任务：

1. 先核验当前可访问项目或上下文中的 Spring AI / Spring Boot / Jackson / Validation 版本；无法访问时采用默认版本基线并说明。
2. 检索并阅读与版本匹配的 Spring AI 官方文档和 JavaDoc。
3. 按本任务书第三章至第八章生成完整知识笔记，确保所有必学知识点都有实质内容，不得只列标题。
4. 对 1.1.x 与 2.x API 差异进行专门核验，避免错误代码。
5. 使用制造业设备故障结构化提取作为贯穿案例，给出端到端代码、校验与测试设计。
6. 对代码、异常、配置和版本进行逐项自检。
7. 生成并保存 Markdown 文件，检查文件是否完整、标题是否齐全、代码块是否闭合、链接是否完整。
8. 最后按第八章验收表进行自检，并如实报告尚未验证的部分。

**最终目标不是让学习者记住几个 API，而是让学习者真正理解：大模型输出如何从不可靠文本，经过结构化转换、类型映射、字段校验和业务验证，成为可以安全进入企业 Java 业务流程的数据。**
