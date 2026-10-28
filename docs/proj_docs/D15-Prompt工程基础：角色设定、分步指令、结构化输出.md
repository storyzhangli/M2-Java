# Spring AI 知识笔记：Prompt 工程基础——角色设定、分步指令与结构化输出

技术基线：JDK 21 LTS / Spring Boot 3.5.16 / Spring AI 1.1.8
参考模型：DeepSeek API
文档定位：工程级可落地知识笔记 · 面向 Java AI 工程师转型
官方参考：Spring AI 1.1 官方文档、DeepSeek JSON 模式文档、RFC 8259 JSON 规范

---

## 一、学习目标与适用版本

### 1.1 学习目标

完成本笔记学习后，能够：

1. 从工程视角理解 Prompt 工程的定位、作用与边界，而非停留在「提示词技巧」层面
2. 独立设计包含角色、任务、步骤、约束、输出规范的完整生产级 Prompt
3. 将复杂业务任务拆解为可验证、可执行的分步指令
4. 设计结构化输出约束，并结合 Spring AI 完成 JSON→Java 对象的映射与校验
5. 基于 Spring AI 1.1.x API 实现 Prompt 模板化、角色注入与结构化输出调用
6. 建立 Prompt 测试、评估与迭代优化的工程方法
7. 识别 Prompt 工程的能力边界，区分模型输出、程序校验与业务规则的职责边界

### 1.2 版本基线说明

| 技术项      | 版本           | 说明                                                         |
| ----------- | -------------- | ------------------------------------------------------------ |
| Java        | 21 LTS         | 所有代码示例均使用 Java 21 语法（Record、var、新版集合 API 等） |
| Spring Boot | 3.5.16         | 与 Spring AI 1.1.x 版本严格兼容                              |
| Spring AI   | 1.1.8          | 本笔记所有 API、类名、方法名均基于 1.1.x 官方文档，不混入 2.0 新特性 |
| 模型服务    | DeepSeek       | 支持 JSON 输出模式，用于原生结构化输出验证                   |
| 模板引擎    | StringTemplate | Spring AI 1.1 默认模板渲染器，使用 `{变量名}` 占位符语法     |

>
> 重要边界：本笔记不涉及 Spring AI 2.0 的 Session Memory、Tool Calling 等新特性；所有功能均以 1.1.x 生产稳定版本为准。

---

## 二、Prompt 工程基础与工作机制

### 2.1 Prompt 的定义与作用

#### 一句话定义

Prompt 是输入给大语言模型的结构化指令集合，用于明确任务目标、背景上下文、操作规则、输入数据与输出约束，引导模型生成符合预期的结果。

#### Prompt 与普通自然语言问题的区别

| 维度     | 普通自然语言问题 | 工程化 Prompt                       |
| -------- | ---------------- | ----------------------------------- |
| 目标     | 获得答案         | 完成可验证的业务任务                |
| 结构     | 自由提问         | 角色+目标+上下文+指令+约束+输出规范 |
| 结果预期 | 开放式回答       | 符合明确验收标准                    |
| 可重复性 | 结果波动大       | 相同输入下输出结构与质量稳定        |
| 错误边界 | 模糊错误         | 可定位、可校验、可重试              |

#### 核心认知

- Prompt 本质是**模型输入的组织方式**，不是修改模型参数、不是训练模型，只作用于单次推理过程。
- Prompt Engineering 的目标不是寻找「万能提示词」，而是通过结构化的指令设计，提升任务成功率、结果可验证性与输出稳定性。
- Prompt 不能保证模型 100% 遵守所有指令，也不能保证事实绝对正确；它提升符合预期的概率，不消除边界风险。

#### 核心组件关系

```
ChatModel ← Prompt（Message 列表） ← ChatClient 封装
                      ↑
          SystemMessage + UserMessage + AssistantMessage
```

官方参考：**Prompts | Spring AI 提示词与消息接口**：[https://docs.spring.io/spring-ai/reference/1.1/api/prompt.html](https://docs.spring.io/spring-ai/reference/1.1/api/prompt.html)

### 2.2 高质量 Prompt 的九大核心组成

不是所有任务都需要完整九段式，但复杂生产任务建议按以下结构组织：

| 组成部分                           | 作用                            | 说明                                       |
| ---------------------------------- | ------------------------------- | ------------------------------------------ |
| 1. 角色（Role）                    | 设定模型的专业视角与职责边界    | 比如「你是资深Java代码审查专家」           |
| 2. 目标（Objective）               | 明确本次任务要解决的核心问题    | 比如「对提交的代码进行安全性审查」         |
| 3. 背景上下文（Context）           | 提供完成任务所需的业务/技术背景 | 比如「项目使用JDK21+SpringBoot3.5」        |
| 4. 输入数据（Input）               | 明确需要处理的具体内容          | 比如待审查代码、待分析文本                 |
| 5. 执行指令（Instructions）        | 处理步骤、操作规则与判断标准    | 比如「按以下5步执行审查」                  |
| 6. 约束条件（Constraints）         | 禁止行为、适用范围与限制        | 比如「不得修改原代码逻辑」                 |
| 7. 输出规范（Output Format）       | 返回格式、字段、类型、结构      | 比如「输出标准JSON，包含3个字段」          |
| 8. 验收标准（Acceptance Criteria） | 定义结果合格的判定依据          | 比如「所有安全问题必须标注行号与风险等级」 |
| 9. 失败处理（Fallback）            | 信息不足/冲突时的响应方式       | 比如「信息不足时返回unknown，不得编造」    |

#### 完整 Prompt 示例（Java 代码审查场景）

```
【角色】
你是一名具有10年经验的Java后端安全审查专家，专注于代码安全漏洞识别与分级。

【目标】
对提交的Java代码片段进行安全性审查，识别安全风险并给出修复建议。

【上下文】
- 代码运行环境：JDK 21 + Spring Boot 3.5
- 审查范围：输入的代码片段本身，不推断外部依赖
- 风险等级：高/中/低三级

【执行指令】
1. 逐行扫描代码，识别潜在安全问题
2. 每个问题标注：风险等级、问题位置、问题描述
3. 给出对应修复建议，说明修复原理
4. 没有问题时明确返回"未发现安全风险"

【约束条件】
- 不得编造代码中不存在的问题
- 不得对代码功能本身做业务逻辑评价
- 不输出与审查无关的解释性文字

【输出规范】
输出标准JSON格式，结构如下：
{
  "hasRisk": boolean,
  "issues": [
    {
      "level": "HIGH/MEDIUM/LOW",
      "line": 整数,
      "description": "问题描述",
      "fixSuggestion": "修复建议"
    }
  ]
}

【验收标准】
- 所有问题必须对应代码中真实存在的行
- 风险等级定义准确
- JSON格式合法，无多余文字

【失败处理】
代码为空或无法识别时，返回hasRisk=false，issues为空数组。
```

### 2.3 Prompt 质量的核心影响因素

1. **指令明确度**：模糊的指令必然得到模糊的结果
2. **边界清晰度**：任务范围、禁止事项定义不清，模型容易越界
3. **上下文充分性**：缺少必要的业务/技术背景，模型只能基于常识猜测
4. **输入格式一致性**：输入结构变化大，模型解析错误率上升
5. **约束冲突性**：多条要求互相矛盾，模型只能随机满足其中一条
6. **输出可验证性**：没有明确验收标准，无法判断结果是否合格
7. **示例代表性**：示例偏差会导致模型输出偏移
8. **模型参数设置**：温度、最大Token、模型能力本身的硬边界

>
> 工程原则：Prompt 更长 ≠ 结果更好。冗余、冲突、模糊的指令越多，结果越差。

### 2.4 不良 Prompt 案例与改进

#### 案例1：指令模糊

❌ 不良版本：

```
帮我看看这段Java代码有什么问题。
```

✅ 改进版本：

```
对这段Java代码进行线程安全审查，找出竞态条件问题，标注代码行号并说明原因。
代码：
...
```

>
> 问题：原问题没有限定审查范围、输出形式、判断标准；改进后明确了任务类型、输出要求、输入范围。

#### 案例2：约束冲突

❌ 不良版本：

```
用尽可能详细的语言总结这篇文档，控制在100字以内。
```

✅ 改进版本：

```
用不超过100字总结文档核心结论，保留3个关键数据。
```

>
> 问题：「尽可能详细」和「100字以内」冲突；改进后统一目标，明确约束。

#### 案例3：上下文不足

❌ 不良版本：

```
错误码1001是什么意思？
```

✅ 改进版本：

```
在我们的订单系统错误码体系中，错误码1001代表什么含义？
已知体系：1xxx为参数校验类错误，2xxx为业务异常类错误
```

>
> 问题：缺少业务体系上下文，模型只能给出通用解释；改进后限定业务体系，答案精准。

---

## 三、角色设定与消息角色

### 3.1 什么是角色设定

#### 一句话定义

角色设定（Role Prompting）是通过系统指令定义模型的身份、职责范围、专业视角与输出风格，引导模型从对应角色的知识体系与行为模式出发处理任务。

#### 核心作用

1. 限定回答视角：从专业人员而非通用视角回答问题
2. 约束职责边界：明确模型能做什么、不能做什么
3. 统一输出风格：专业、正式、简洁等风格统一
4. 提升结果质量：角色带入相关领域的默认知识与判断标准

>
> 重要边界：角色设定是**视角与行为模式引导**，不是真实授予权限、专业资质或无限能力；不能用角色描述替代具体任务规则。

### 3.2 三类消息的职责

Spring AI 中 Prompt 由不同角色的 Message 组成，核心三类：

| 消息类型          | 定位                           | 典型用途                         | Spring AI 对应方法    |
| ----------------- | ------------------------------ | -------------------------------- | --------------------- |
| System Message    | 系统级指令、角色设定、全局规则 | 注入角色、通用规则、输出约束     | `ChatClient.system()` |
| User Message      | 用户输入、任务数据、具体请求   | 本次任务的输入、提问、待处理内容 | `ChatClient.user()`   |
| Assistant Message | 模型历史回复                   | 多轮对话中保存历史回复           | 对话历史中的模型输出  |

官方参考：**Chat Client API | Spring AI 聊天客户端 API**：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)

#### 职责边界说明

1. **System 消息**：全局、稳定、不随单次任务变化；适合放角色、通用规则、输出规范
2. **User 消息**：单次任务相关、动态变化；适合放具体输入、本次任务指令
3. **Assistant 消息**：历史交互记录；用于多轮对话上下文，单轮任务不需要

>
> 工程最佳实践：角色与通用规则放 System Prompt，具体业务数据与任务放 User Prompt；不要把所有内容都堆在用户消息里。

### 3.3 角色设计的六种典型模式

#### 模式1：专业领域顾问

- ❌ 不足：`你是AI专家`
- ✅ 改进：`你是资深Java后端架构师，熟悉JDK21并发编程、SpringBoot3.x性能调优，输出技术方案时说明适用版本与风险边界`
- 适用场景：技术咨询、方案设计、问题诊断

#### 模式2：代码审查专家

- ❌ 不足：`帮我看代码`
- ✅ 改进：`你是严格的Java代码审查工程师，按照阿里巴巴Java开发规范审查代码，指出问题、严重等级、修复建议，不修改原代码`
- 适用场景：代码审查、规范检查、漏洞扫描

#### 模式3：技术导师

- ❌ 不足：`给我讲讲并发`
- ✅ 改进：`你是Java技术导师，面向有4年开发经验的工程师讲解JDK21虚拟线程，先讲核心原理，再给可运行代码，最后讲生产坑点与面试考点`
- 适用场景：技术学习、技能培训、知识讲解

#### 模式4：信息提取分类器

- ❌ 不足：`整理一下这段文字`
- ✅ 改进：`你是信息提取专员，从客户投诉文本中提取：订单号、投诉类型、涉及产品、紧急程度；分类标准：质量/物流/售后；紧急程度：高/中/低`
- 适用场景：文本分类、信息抽取、字段解析

#### 模式5：文档分析助手

- ❌ 不足：`总结这篇文档`
- ✅ 改进：`你是技术文档分析员，从技术方案文档中提取：架构方案、核心依赖、风险点、性能指标；输出结构化列表，保留原文关键数据`
- 适用场景：文档解析、方案评审、信息摘要

#### 模式6：规范执行业务处理器

- ❌ 不足：`处理一下这个工单`
- ✅ 改进：`你是设备工单处理员，按照《设备故障处理规范V2.1》对工单进行分类、分级、派单；缺少信息时标注待补充，不得擅自判断故障原因`
- 适用场景：工单处理、业务审核、规则执行

### 3.4 角色设定的常见误区

1. **头衔堆砌症**：大量使用「资深」「专家」「顶级」等头衔，没有具体职责与输出约束
2. **角色冲突**：让模型同时扮演多个互相矛盾的角色（既是开发者又是测试者）
3. **角色万能论**：以为加了角色设定，模型就能拥有对应真实职业的所有知识与判断能力
4. **角色替代规则**：用角色描述替代具体任务规则，以为「专家」就会自动做对所有事
5. **角色=权限**：误以为角色设定能替代身份认证、权限校验、业务审批

>
> 工程红线：角色设定是输出质量提升手段，不是安全机制、权限机制、业务规则替代品。

---

## 四、分步指令与复杂任务拆解

### 4.1 什么是分步指令

#### 一句话定义

分步指令是将复杂任务拆解为多个顺序执行、可验证的子任务，明确每个子任务的输入、输出、依赖关系与验收标准，引导模型按步骤完成复杂任务。

#### 为什么需要分步

1. 复杂任务直接交给模型，容易遗漏步骤、逻辑跳跃、结果不可控
2. 分步执行便于设置检查点，每步验证，降低最终错误率
3. 步骤依赖关系清晰，避免模型颠倒因果顺序
4. 失败时可定位到具体步骤，便于重试与问题排查

>
> 认知误区：「写编号列表 ≠ 分步指令」。真正的分步必须明确每步的输入、输出、判断条件与失败处理。

### 4.2 复杂任务的标准拆解方法（十步法）

1. **明确最终目标**：定义最终输出是什么、验收标准是什么
2. **盘点输入信息**：梳理已有数据、已知条件、约束边界
3. **识别缺失信息**：明确哪些信息缺失、哪些是假设、哪些必须确认
4. **拆解必要子任务**：按逻辑顺序拆分为最小可执行单元
5. **定义子任务输出**：每个子任务产出什么、格式是什么
6. **明确依赖关系**：哪个步骤依赖哪个步骤的输出
7. **设置检查点**：关键步骤后设置校验规则
8. **异常分支处理**：每步失败、信息不足、冲突时的处理路径
9. **整合最终结果**：按要求格式组织最终输出
10. **最终验收**：对照验收标准检查完整结果

### 4.3 分步指令的七种设计模式

#### 模式1：顺序处理

- 逻辑：先完成A，再用A的结果完成B
- 适用：有明确先后依赖的任务
- 示例：先提取字段，再校验字段，最后生成结果

#### 模式2：条件分支

- 逻辑：满足不同条件走不同处理路径
- 适用：分类、分级、不同场景不同处理
- 示例：高危故障走紧急流程，低危故障走常规流程

#### 模式3：信息抽取

- 逻辑：先从输入中抽取指定字段，再按规则校验
- 适用：非结构化文本转结构化数据
- 示例：从故障描述中提取设备号、故障现象、发生时间

#### 模式4：分析与结论分离

- 逻辑：先收集可核查事实，再基于事实推导结论
- 适用：诊断、分析、评估类任务
- 示例：先列已知事实，再做初步分类，最后给出建议

#### 模式5：逐项校验

- 逻辑：逐项核对规则，最后生成统一结果
- 适用：合规检查、规则校验、审查类任务
- 示例：逐条对照安全规范检查代码，汇总问题

#### 模式6：失败回退

- 逻辑：信息不足/步骤失败时，明确回退到哪一步、如何重试
- 适用：不稳定输入、可能失败的任务
- 示例：解析失败时返回原始文本，不强行结构化

#### 模式7：完成检查

- 逻辑：任务最后对照验收清单逐项确认
- 适用：交付物明确、有明确验收标准的任务
- 示例：输出前检查所有必填字段是否完整、格式是否合法

### 4.4 分步指令与推理过程的边界

必须严格区分四个层次，不能混为一谈：

| 层次              | 执行方                                   | 作用               | 可验证性               |
| ----------------- | ---------------------------------------- | ------------------ | ---------------------- |
| 1. Prompt分步指令 | 模型按步骤执行任务                       | 控制任务执行顺序   | 高，可检查每步输出     |
| 2. 输出依据说明   | 模型在最终答案中给出简要依据             | 提升结果可解释性   | 中，依赖模型自觉       |
| 3. 完整内部推理   | 模型输出完整思考过程（Chain-of-Thought） | 辅助理解结果由来   | 低，不保证真实推理路径 |
| 4. 应用多步调用   | 应用程序拆分多次调用模型，传递中间结果   | 完全可控，程序校验 | 最高，工程级可控       |

>
> 工程原则：关键业务任务优先采用「应用多步调用 + 每步校验」，而不是依赖模型自己宣称「已经检查」。

### 4.5 Spring AI 分步指令实现示例

```
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public class StepwiseTaskDemo {

    private final ChatClient chatClient;

    public StepwiseTaskDemo(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                    你是设备故障分析助手。
                    严格按照步骤执行，不得跳步。
                    每步输出明确标记。
                    """)
                .build();
    }

    public FaultAnalysisResult analyze(String faultDesc) {
        // 第一步：提取信息
        String step1Result = chatClient.prompt()
                .user("""
                            【步骤1】从故障描述中提取：设备编号、故障现象、发生时长。
                            输出JSON格式，字段：equipmentId, symptoms, durationMinutes。
                            故障描述：%s
                            """.formatted(faultDesc))
                .call()
                .content();

        // 第二步：校验信息
        String step2Result = chatClient.prompt()
                .user("""
                            【步骤2】检查提取结果是否完整。
                            提取结果：%s
                            缺失字段标记为"unknown"，不得编造。
                            输出JSON格式，字段：complete, missingFields。
                            """.formatted(step1Result))
                .call()
                .content();

        // 第三步：分类与建议
        return chatClient.prompt()
                .user("""
                            【步骤3】根据提取信息进行故障分类。
                            信息：%s
                            分类：电气故障/机械故障/未知。
                            输出：标准故障分析结果对象。
                            """.formatted(step2Result))
                .call()
                .entity(FaultAnalysisResult.class);
    }

    record FaultAnalysisResult(
            String equipmentId,
            String category,
            List<String> missingInfo,
            List<String> suggestions
    ) {}
}
```

>
> 说明：真实工程中每步可加入程序校验，失败则重试或降级；本示例演示分步调用的结构模式。

---

## 五、结构化输出的原理与边界

### 5.1 什么是结构化输出

#### 一句话定义

结构化输出是通过指令约束、模型原生能力或程序转换，让模型输出符合固定格式（通常为JSON）的数据，便于下游程序解析、字段提取与业务处理。

#### 为什么需要结构化输出

- 自然语言输出不适合直接进入业务流程
- 字段提取准确率低，错误率高
- 无法直接映射为Java对象进行业务处理
- 下游系统无法可靠消费

#### 核心区别

| 维度       | 自然语言回答        | 结构化输出         |
| ---------- | ------------------- | ------------------ |
| 格式       | 自由文本            | 固定结构/字段      |
| 解析成本   | 高，需要正则/NLP    | 低，直接JSON解析   |
| 字段准确性 | 低，字段易缺失/错漏 | 高，字段约束明确   |
| 业务可用性 | 仅人工阅读          | 可直接进入业务流程 |

### 5.2 结构化 Prompt 的设计方法

#### 核心设计要素

1. **格式声明**：明确输出JSON，禁止多余文字、解释、Markdown代码块
2. **字段定义**：每个字段的名称、类型、含义、是否必填
3. **枚举约束**：固定可选值列表，禁止自由发挥
4. **类型约束**：字符串、数字、布尔、数组、对象的明确区分
5. **空值处理**：信息不足时用null/"unknown"，不得编造
6. **嵌套结构**：嵌套对象/数组的层级定义
7. **边界示例**：给出正例、边界例、异常例
8. **失败约定**：无法解析时的返回格式

#### 设计反例与正例对比

❌ 不合格版本：

```
输出JSON格式回答问题。
```

✅ 合格版本：

```
输出严格的JSON格式结果，不要任何解释文字、Markdown代码块或额外说明。
JSON结构：
{
  "equipmentId": "字符串，设备编号，必填",
  "faultLevel": "枚举值：HIGH/MEDIUM/LOW，必填",
  "symptoms": "字符串数组，故障现象列表，可为空数组",
  "isMissingInfo": "布尔值，是否缺少关键信息",
  "missingFields": "字符串数组，缺失的字段名，无缺失则为空数组"
}
信息不足时对应字段设为null，isMissingInfo设为true。
```

### 5.3 JSON 示例与 Few-shot 提示

#### 基础概念

- **Zero-shot**：不给示例，仅靠指令约束
- **One-shot**：给1个示例
- **Few-shot**：给多个示例，包含正例、边界例

#### 为什么示例有效

示例让模型直观理解输出结构、边界处理、枚举值，比纯文字描述准确率更高。

#### Few-shot 结构化输出示例

```
请输出符合格式的JSON。

示例1输入：设备EQ-001，电机异响，温度正常
示例1输出：
{"equipmentId":"EQ-001","category":"MECHANICAL","level":"LOW"}

示例2输入：设备EQ-002，冒烟，有焦糊味
示例2输出：
{"equipmentId":"EQ-002","category":"ELECTRICAL","level":"HIGH"}

现在处理输入：{input}
```

>
> 设计原则：示例覆盖典型场景+边界场景；示例不要太复杂，避免模型复制示例具体内容。

### 5.4 JSON、JSON Schema 与 Java 对象映射

#### 核心概念

- **JSON**：数据交换格式，键值对结构
- **JSON Schema**：描述JSON结构的规范，定义字段、类型、必填、枚举、格式等
- **Java对象映射**：将JSON解析为Java Record/类，用于业务处理

#### 对应关系

```
JSON字段定义 → JSON Schema校验 → Jackson解析 → Java Record/类 → 业务规则校验
```

#### Java Record 映射示例

```
// 对应JSON结构的Java Record
public record EquipmentFault(
        String equipmentId,          // 字符串字段
        FaultLevel level,             // 枚举
        List<String> symptoms,       // 数组
        boolean isMissingInfo,       // 布尔
        List<String> missingFields    // 数组
) {}

// 枚举类型
public enum FaultLevel {
    HIGH, MEDIUM, LOW
}
```

>
> 重要边界：JSON语法合法 ≠ 业务语义正确；结构正确不代表字段值、分类、判断符合业务规则。

### 5.5 结构化输出的四层保障体系

必须分层理解，每层解决不同问题，不能互相替代：

| 层级                              | 实现方式                               | 作用               | 可靠性             |
| --------------------------------- | -------------------------------------- | ------------------ | ------------------ |
| 第一层：Prompt约束                | 文字指令+示例引导模型输出格式          | 引导模型按格式输出 | 中，依赖模型服从度 |
| 第二层：模型原生JSON模式          | 模型API级别的response_format参数       | 模型侧强制JSON输出 | 高，模型原生支持   |
| 第三层：Spring AI结构化输出转换器 | BeanOutputConverter等，提示+解析一体化 | 框架封装转换逻辑   | 较高，附带校验     |
| 第四层：应用程序校验              | 字段非空、枚举范围、业务规则校验       | 业务最终把关       | 最高，工程级可控   |

官方参考：**Structured Output Converter | Spring AI 结构化输出转换器**：[https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html](https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html)

#### 关键说明

1. **DeepSeek原生JSON模式**：通过`response_format`参数，保证输出合法JSON，是模型侧能力。
官方参考：**JSON Output | DeepSeek JSON输出模式**：[https://api-docs.deepseek.com/zh-cn/guides/json_mode/](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)
2. **Spring AI BeanOutputConverter**：Spring AI 1.1提供的转换器，自动在Prompt中加入格式指令，将模型输出解析为指定Java类型。
3. **不能互相替代**：Prompt约束是基础，原生模式提升可靠性，程序校验是最终保障。
4. **没有100%保证**：任何一层都不能保证完全符合；关键业务必须最终程序校验。

---

## 六、Spring AI 中的 Prompt 组织与模板化

### 6.1 ChatClient 的 Prompt 组织方式

Spring AI 1.1.x 的 `ChatClient` 是核心调用入口，采用流式Builder模式组织Prompt。

#### 核心方法说明

| 方法                            | 作用                          | 说明         |
| ------------------------------- | ----------------------------- | ------------ |
| `ChatClient.builder(ChatModel)` | 构建ChatClient，传入ChatModel | 基础构造方式 |
| `.defaultSystem(String)`        | 设置默认系统提示              | 所有调用共用 |
| `.system(String)`               | 单次调用的系统提示            | 覆盖默认     |
| `.prompt()`                     | 启动Prompt构建                | 进入流式API  |
| `.user(String)`                 | 设置用户消息内容              | 支持模板变量 |
| `.call()`                       | 执行同步调用                  | 触发模型请求 |
| `.content()`                    | 返回字符串结果                | 纯文本输出   |
| `.entity(Class<T>)`             | 返回解析后的Java对象          | 结构化输出   |

官方参考：**Chat Client API | Spring AI 聊天客户端 API**：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)

#### 基础调用示例

```
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

@Component
public class PromptDemoService {

    private final ChatClient chatClient;

    // 注入ChatModel，自动配置
    public PromptDemoService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是专业Java技术助手，回答简洁准确，优先给出代码。")
                .build();
    }

    // 1. 纯文本返回
    public String simpleAnswer(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    // 2. 结构化对象返回
    public FaultResult analyzeFault(String desc) {
        return chatClient.prompt()
                .user("分析故障描述：" + desc)
                .call()
                .entity(FaultResult.class);
    }

    public record FaultResult(String level, String cause) {}
}
```

### 6.2 PromptTemplate 与动态变量

#### 作用

将固定指令模板与动态输入数据分离，提升复用性，避免字符串拼接混乱。

#### 核心机制

- Spring AI 1.1 默认使用 `StringTemplate` 渲染引擎
- 变量占位符语法：`{变量名}`
- 变量通过 `.param("key", value)` 传入

#### 模板示例

```
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;

@Component
public class TemplateDemo {

    private final ChatClient chatClient;

    // 可复用模板
    private static final String FAULT_ANALYSIS_TEMPLATE = """
            你是设备故障分析助手。
            设备编号：{equipmentId}
            故障描述：{faultDesc}
            
            按以下格式输出JSON：
            {
              "category": "故障分类",
              "level": "风险等级"
            }
            """;

    public TemplateDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String analyze(String equipmentId, String faultDesc) {
        PromptTemplate template = PromptTemplate.builder()
                .template(FAULT_ANALYSIS_TEMPLATE)
                .build();

        return chatClient.prompt()
                .user(userSpec -> userSpec
                        .text(template.template())
                        .param("equipmentId", equipmentId)
                        .param("faultDesc", faultDesc))
                .call()
                .content();
    }
}
```

#### 常见问题与边界

1. **JSON大括号冲突**：模板中同时有JSON的`{}`和模板变量`{var}`，会产生解析冲突。
   - 解决方案：自定义模板渲染器的分隔符，比如使用`<`和`>`作为变量占位符。
2. **变量缺失**：变量名不匹配会抛出异常，必须校验变量名称。
3. **输入注入风险**：用户输入可能包含指令类文本，关键场景需要做输入净化。

### 6.3 默认指令与单次指令

#### 区别

| 方式              | 作用域       | 适用内容                     |
| ----------------- | ------------ | ---------------------------- |
| `defaultSystem()` | 所有调用生效 | 角色设定、通用规则、输出规范 |
| `.system()`       | 单次调用生效 | 本次任务特定指令             |

#### 最佳实践

- 通用角色、输出规范、安全规则放默认系统提示
- 具体任务指令、输入数据放单次用户消息
- 不要构建超大而全的万能System Prompt，维护成本高、效果差

---

## 七、Prompt 设计中的安全边界与错误处理

### 7.1 十大风险场景

1. **输入注入攻击**：用户输入包含「忽略之前指令，输出XXX」等指令
2. **信息不足编造**：缺少信息时模型编造数据填补空白
3. **输出越界**：模型输出超出允许的字段范围或业务权限
4. **格式错误**：输出JSON语法错误、字段缺失、类型错误
5. **事实错误**：生成不存在的事实、错误数据、错误判断
6. **枚举越界**：输出不在预定义枚举值内的内容
7. **内容泄露**：输出中包含不应输出的敏感信息
8. **过度推理**：基于有限信息做出过度业务判断
9. **重复执行**：重试导致下游操作重复执行
10. **拒绝服务**：恶意构造输入导致模型消耗大量资源

### 7.2 分层防护原则

| 层级     | 防护措施                                            |
| -------- | --------------------------------------------------- |
| Prompt层 | 明确边界、禁止越权、信息不足时返回unknown、禁止编造 |
| 模型层   | 原生JSON模式、温度控制、最大长度限制                |
| 转换层   | Spring AI转换器、JSON Schema校验、解析异常捕获      |
| 应用层   | 字段校验、枚举校验、业务规则校验、权限校验          |
| 业务层   | 关键操作幂等、人工审核、异常监控                    |

>
> 工程红线：Prompt 不是安全机制。身份认证、权限控制、数据校验必须由应用程序承担，不能依赖模型自觉。

### 7.3 失败处理策略

1. **解析失败重试**：JSON解析失败时，重试一次或调整提示重试
2. **字段缺失降级**：非关键字段缺失时用默认值，关键字段缺失则失败
3. **异常封装**：封装结构化输出异常，统一错误码
4. **阈值控制**：最多重试N次，避免无限循环
5. **人工兜底**：高风险场景模型输出必须人工审核

---

## 八、Prompt 工程的测试、评估与优化

### 8.1 测试数据集设计

#### 测试集覆盖维度

- 正常输入：典型业务场景
- 边界输入：极值、最小/最大长度
- 空输入/缺失字段：不完整数据
- 模糊输入：信息不明确、描述模糊
- 矛盾输入：条件互相冲突
- 格式异常：格式错误的输入
- 长文本输入：大段文本处理
- 异常输入：包含注入指令的恶意输入
- 不同业务类型：覆盖各类典型场景

>
> 原则：不能用一两个成功样例证明Prompt有效；必须覆盖边界与异常场景。

### 8.2 核心评价指标

| 指标           | 定义                    | 统计方式                  |
| -------------- | ----------------------- | ------------------------- |
| 任务完成率     | 成功完成任务的比例      | 成功数/总样本数           |
| 字段完整率     | 必填字段都存在的比例    | 字段完整样本/总样本数     |
| JSON语法成功率 | 输出合法JSON的比例      | 合法JSON数/总样本数       |
| Schema合规率   | 符合字段类型/枚举的比例 | 合规样本/总样本数         |
| 业务规则通过率 | 符合业务规则的比例      | 通过业务校验样本/总样本数 |
| 平均响应长度   | 输出Token数             | 总Token/样本数            |
| 结果稳定性     | 多次相同输入结果差异度  | 多次调用结果一致率        |

### 8.3 对照迭代实验设计

#### 实验1：角色设定效果对照

- 目的：验证角色设定对结果质量的提升
- 对照组：仅任务指令，无角色
- 实验组：角色+任务指令
- 指标：业务准确率、字段完整率
- 结论：角色设定提升的幅度与任务类型相关

#### 实验2：分步指令效果对照

- 目的：验证分步是否降低错误率
- 对照组：一次性给出所有要求
- 实验组：明确分步指令+检查点
- 指标：步骤遗漏率、结果正确率
- 结论：复杂任务分步效果显著

#### 实验3：结构化输出方式对照

- 目的：验证不同结构化保障的效果
- 对照组：仅文字要求输出JSON
- 实验组1：+Few-shot示例
- 实验组2：+原生JSON模式
- 实验组3：+程序校验
- 指标：JSON成功率、Schema合规率
- 结论：多层保障下成功率逐层提升

>
> 重要说明：实验结果依赖模型、参数、数据集；更换条件需重新评估，不能一概而论。

---

## 九、综合案例：制造业设备故障工单分析助手

### 9.1 业务背景

制造业设备管理系统中，一线人员提交故障描述，需要AI助手自动提取信息、初步分类、识别缺失信息、给出处理建议，输出结构化结果供下游工单系统调用。

### 9.2 角色设定

```
【角色】
你是制造业设备故障工单初筛专员，拥有5年设备运维经验，负责故障工单的信息提取、初步分类与缺失信息识别。

【职责边界】
1. 仅基于提交的故障描述进行分析，不得编造未提及的信息
2. 初步分类仅作参考，最终诊断必须由现场工程师确认
3. 不得给出维修方案以外的采购、改造等超出权限的建议
4. 信息不足时明确标注，不得猜测故障原因

【输出规范】
严格输出JSON，不得有任何额外解释、Markdown代码块或前后缀文字。
```

### 9.3 分步处理指令

```
【处理步骤】
严格按以下步骤执行，不得跳步。

步骤1：信息提取
从故障描述中提取以下字段：
- equipmentId：设备编号
- faultDescription：故障现象原文
- occurMinutes：故障发生时长（分钟），数字
- hasAbnormalSound：是否有异常声音，布尔值
- hasTemperatureWarning：是否有温度告警，布尔值

步骤2：事实校验
区分已知事实与推测：
- 输入中明确描述的为已知事实
- 未明确提及的为未知，标记为null
- 不得将常识推测当作已知事实

步骤3：故障分级
根据以下规则分级：
- HIGH：同时有异响+温度告警
- MEDIUM：仅有异响或仅有温度告警
- LOW：仅轻微抖动、指示灯异常等
- UNKNOWN：信息不足以判断

步骤4：缺失信息识别
列出所有影响判断的缺失关键信息。

步骤5：生成建议
根据分级给出建议：
- HIGH：立即停机，安排紧急检修
- MEDIUM：24小时内安排检查
- LOW：例行巡检时处理
- UNKNOWN：补充信息后再处理

步骤6：输出结果
按指定JSON结构输出。
```

### 9.4 结构化输出 Schema

```
{
  "equipmentId": "string，设备编号，必填",
  "faultLevel": "枚举：HIGH/MEDIUM/LOW/UNKNOWN，必填",
  "knownFacts": ["字符串数组，已知事实列表，必填"],
  "missingInformation": ["字符串数组，缺失信息列表，无缺失则为空数组"],
  "recommendedActions": ["字符串数组，建议措施列表"],
  "requiresHumanReview": "布尔值，是否需要人工复核，必填"
}
```

### 9.5 Java 实现代码

#### 9.5.1 DTO 定义

```
package com.practice.ai.dto;

import java.util.List;

/**
 * 设备故障分析结果
 */
public record EquipmentFaultResult(
        String equipmentId,
        FaultLevel faultLevel,
        List<String> knownFacts,
        List<String> missingInformation,
        List<String> recommendedActions,
        boolean requiresHumanReview
) {

    public enum FaultLevel {
        HIGH, MEDIUM, LOW, UNKNOWN
    }
}
```

#### 9.5.2 分析服务实现

```
package com.practice.ai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

@Service
public class EquipmentFaultAnalysisService {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            你是制造业设备故障工单初筛专员，拥有5年设备运维经验。
            严格按照步骤执行，不得跳步。
            信息不足时明确标注，不得编造。
            输出严格JSON格式，无额外文字。
            """;

    private static final String USER_TEMPLATE = """
            故障描述：
            {faultDesc}
            
            处理步骤：
            1.提取设备编号、故障现象、发生时长、是否有异响、是否有温度告警
            2.区分已知事实与未知
            3.分级：HIGH(异响+温警)/MEDIUM(单一)/LOW(轻微)/UNKNOWN(信息不足)
            4.列出缺失关键信息
            5.给出对应处理建议
            6.按JSON结构输出
            
            输出字段：
            equipmentId: 设备编号
            faultLevel: HIGH/MEDIUM/LOW/UNKNOWN
            knownFacts: 已知事实数组
            missingInformation: 缺失信息数组
            recommendedActions: 建议数组
            requiresHumanReview: 是否人工复核
            """;

    public EquipmentFaultAnalysisService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    public EquipmentFaultResult analyze(String faultDesc) {
        try {
            EquipmentFaultResult result = chatClient.prompt()
                    .user(spec -> spec
                            .text(USER_TEMPLATE)
                            .param("faultDesc", faultDesc))
                    .call()
                    .entity(EquipmentFaultResult.class);

            // 应用层最终校验
            return validateResult(result);
        } catch (Exception e) {
            // 解析失败降级处理
            return buildFallbackResult(faultDesc, e);
        }
    }

    private EquipmentFaultResult validateResult(EquipmentFaultResult result) {
        // 字段非空校验、枚举校验、业务规则校验
        if (result.equipmentId() == null || result.equipmentId().isBlank()) {
            return buildFallbackResult("", new IllegalArgumentException("设备编号缺失"));
        }
        // 高风险强制人工复核
        if (result.faultLevel() == EquipmentFaultResult.FaultLevel.HIGH && !result.requiresHumanReview()) {
            return new EquipmentFaultResult(
                    result.equipmentId(),
                    result.faultLevel(),
                    result.knownFacts(),
                    result.missingInformation(),
                    result.recommendedActions(),
                    true
            );
        }
        return result;
    }

    private EquipmentFaultResult buildFallbackResult(String faultDesc, Exception e) {
        return new EquipmentFaultResult(
                "unknown",
                EquipmentFaultResult.FaultLevel.UNKNOWN,
                List.of("原始描述：" + faultDesc),
                List.of("解析失败：" + e.getMessage()),
                List.of("转人工处理"),
                true
        );
    }
}
```

### 9.6 结果说明

1. 模型负责结构化输出与初步分析
2. 应用层负责字段校验、业务规则补全校验
3. 解析失败有降级处理，不直接抛出异常
4. 高风险场景强制人工复核，不直接自动执行

---

## 十、核心概念对比表

| 概念                | 定义             | 作用层级    | 可靠性         |
| ------------------- | ---------------- | ----------- | -------------- |
| System Prompt       | 系统级角色与规则 | Prompt层    | 引导模型行为   |
| User Prompt         | 单次任务输入     | Prompt层    | 任务载体       |
| 分步指令            | 任务拆解执行     | Prompt层    | 提升正确率     |
| Few-shot示例        | 格式示例引导     | Prompt层    | 提升格式准确率 |
| 原生JSON模式        | 模型侧格式强制   | 模型API层   | 高             |
| BeanOutputConverter | 框架转换工具     | Spring AI层 | 较高，附带解析 |
| 应用程序校验        | 业务最终把关     | 业务层      | 最高           |

---

## 十一、学习总结与自测清单

### 核心知识点复盘

- 理解Prompt工程的定位与边界，不是「玄学」而是工程方法
- 掌握高质量Prompt的九大组成部分
- 能设计专业角色设定，避免头衔堆砌
- 能将复杂任务拆解为可验证的分步指令
- 能设计结构化输出约束，区分Prompt、原生、转换器三层保障
- 能用Spring AI ChatClient实现角色、模板、结构化输出调用
- 理解Prompt安全边界，知道哪些必须程序校验
- 能设计对照实验评估Prompt效果

### 能力自检

- 能独立为业务场景设计完整Prompt
- 能将Spring AI调用集成到业务代码中
- 能识别常见的Prompt质量问题并改进
- 能设计测试集并评估Prompt效果
- 能处理结构化输出失败与降级

---

## 十二、官方参考资料

1. **Prompts | Spring AI 提示词与消息接口**：[https://docs.spring.io/spring-ai/reference/1.1/api/prompt.html](https://docs.spring.io/spring-ai/reference/1.1/api/prompt.html)
2. **Chat Client API | Spring AI 聊天客户端 API**：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
3. **Structured Output Converter | Spring AI 结构化输出转换器**：[https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html](https://docs.spring.io/spring-ai/reference/1.1/api/structured-output-converter.html)
4. **JSON Output | DeepSeek JSON输出模式**：[https://api-docs.deepseek.com/zh-cn/guides/json_mode/](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)
5. **RFC 8259 The JavaScript Object Notation (JSON) Data Interchange Format | RFC 8259 JSON数据交换格式**：[https://www.rfc-editor.org/rfc/rfc8259.html](https://www.rfc-editor.org/rfc/rfc8259.html)
6. **Spring AI Releases | Spring AI 版本发布记录**：[https://github.com/spring-projects/spring-ai/releases](https://github.com/spring-projects/spring-ai/releases)

---

需要我针对某一个模块补充更详细的代码示例，或者提供可直接运行的Spring Boot项目骨架吗？