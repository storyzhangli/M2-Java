# D23 项目迭代任务书：Mockito 显式 Java Agent 与 Maven 测试构建规范化

> **目标：**保留 Mockito Inline Mock Maker，将当前项目实际使用的 `mockito-core` 显式作为测试 JVM 的 Java Agent；统一由 Maven Surefire 管理，若项目使用 Failsafe，则为集成测试 JVM 单独配置。  
> **范围：**仅规范测试构建环境；不修改 Spring Boot 业务代码、不修改现有测试逻辑、不升级无关依赖。

## 1. 本轮参数

| 项目 | 路径 / 要求 |
|---|---|
| 迭代编号 | `D23`（本附件所有目录、需求编号和文档均使用 D23） |
| 项目根目录 | `E:\AI\project\AI-Learning-one\M2-Java\D23` |
| 根 POM | `E:\AI\project\AI-Learning-one\M2-Java\D23\pom.xml` |
| 扫描与骨架目录 | `E:\AI\project\AI-Learning-one\M2-Java\D23\D23_CRTFC_SSE_MASO` |
| 正式文档 | `E:\AI\project\AI-Learning-one\M2-Java\docs\proj_docs` |
| 日志 | `E:\AI\project\AI-Learning-one\M2-Java\logs\D23` |
| 临时文件 | `E:\AI\project\AI-Learning-one\M2-Java\docs\temp_docs\D23` |
| 验收证据 | `E:\AI\project\AI-Learning-one\M2-Java\docs\evidence\D23` |

**迭代编号规则：**本轮统一使用 `D23`、`D23-R01` 等编号，不沿用其他迭代编号，不使用泛化的 `Dxx` 作为实际目录或文件名。

## 2. 功能要求与边界

### 需求

- **D23-R01：保留 Inline Mock Maker。** 保留当前项目的 Mockito Inline Mock Maker 能力及相关配置；不得通过删除或替换 `mockito-extensions/org.mockito.plugins.MockMaker`、更换 Mock Maker 或移除 Mockito 依赖来规避警告。
- **D23-R02：显式 Java Agent。** 将项目当前解析到的 `org.mockito:mockito-core` JAR 作为 `-javaagent` 传入测试 JVM。Agent 版本必须与实际测试依赖版本一致，不得另行指定一个不同版本。
- **D23-R03：Surefire 统一配置。** 使用 Maven Surefire 统一管理单元测试 JVM 参数，不在测试类、测试基类或单个测试方法中处理 Agent。
- **D23-R04：Failsafe 条件配置。** 只有确认项目实际配置并执行 Maven Failsafe 时，才为 Failsafe 的集成测试 JVM 单独配置 Agent；不得为了本需求新增 Failsafe 或改变集成测试划分。
- **D23-R05：最小改动。** 不升级无关依赖、不修改业务源码、不改变现有测试逻辑，不使用隐藏或关闭警告的 JVM 参数替代显式 Agent。

### 明确禁止

- 不添加 `-XX:+EnableDynamicAgentLoading`、`-Djdk.instrument.traceUsage` 或其他仅为隐藏警告/改变警告呈现的参数。
- 不更换 Inline Mock Maker，不删除静态/构造器/Final 类 Mock 等既有测试能力。
- 不升级 Mockito、Spring Boot、JDK、Surefire、Failsafe 或其他插件，除非发现现有配置确实无法完成目标；如必须调整，先说明原因、影响和最小方案。
- 不修改任何业务 Java 文件或现有测试类/测试方法。
- 不覆盖用户已有未提交修改，不执行 `git reset --hard`、`git clean` 等破坏性操作，不自动删除临时文件。

## 3. 执行协议

### 3.1 先做脚本化静态检查

在项目根目录确认 `pom.xml`、模块关系和 Git 状态。使用 PowerShell/Python 等本地脚本读取 XML，不进行全仓库源码扫描。检查范围仅限：

1. 根 `pom.xml` 及本轮相关模块 POM；
2. `D23_CRTFC_SSE_MASO` 中与构建继承关系直接相关的 POM；
3. 项目索引中与 Mockito、Surefire、Failsafe、测试依赖和构建插件相关的记录。

脚本输出精简报告至 `docs\temp_docs\D23`，至少提取：

- JDK/Maven 基线、父子 POM 和实际测试模块；
- `mockito-core`、`mockito-inline`、`spring-boot-starter-test` 的声明及最终解析版本/范围；
- `maven-dependency-plugin`、`maven-surefire-plugin`、`maven-failsafe-plugin` 的版本、继承位置、Profile、Execution；
- 已有 `argLine`、`forkCount`、JaCoCo 或其他会修改/依赖 JVM 参数的配置；
- `mockito-extensions/org.mockito.plugins.MockMaker` 文件是否存在及其内容；
- Git 当前分支、工作区状态、相关 POM 的哈希和差异。

日志写入 `logs\D23`。不得把完整扫描输出直接交给模型，只提供摘要、相关片段和异常。

### 3.2 按需读取项目资料

默认禁止全仓库源码读取。先查项目索引与 POM 引用关系；本次通常只需读取相关 POM、构建 Profile、Mockito 扩展配置及测试报告。若需读取其他文件，必须说明它与 D23 构建链路的直接关系。仅在索引缺失、POM 版本变化或继承关系无法确认时，允许局部重新扫描。

### 3.3 技术方案判断

以项目解析到的 Mockito 版本为准，不自行假定版本。优先采用 Mockito 官方 Maven 示例的机制：

1. 通过 `maven-dependency-plugin` 的 `properties` Goal 获取当前项目依赖 JAR 的实际文件路径。该 Goal 默认绑定 `initialize` 阶段，并为解析到的依赖建立 `groupId:artifactId:type` 形式的属性。
2. 在 `maven-surefire-plugin` 的 `argLine` 中追加 `-javaagent:${org.mockito:mockito-core:jar}`。
3. 保留已有 `argLine` 中的 JaCoCo、内存、系统属性及其他必要参数；不得用新配置覆盖原参数。
4. 若采用 Surefire 的 `@{argLine}` 延迟属性替换，必须检查该属性是否已被其他插件设置；如不存在，按 Mockito 官方说明提供安全的空初始值，避免测试 JVM 将字面量 `@{argLine}` 当作参数导致启动失败。
5. 检查 Surefire 是否使用 forked JVM。`argLine` 只对 forked execution 生效；若 `forkCount=0`，先记录其现状和影响，再提出最小调整方案，不得静默改变既有并发/隔离行为。
6. 若项目存在 Failsafe，独立检查它的 `argLine`、属性来源及已有参数，并单独追加同版本 Mockito Agent；不得假设 Surefire 的配置会自动作用于 Failsafe。

只修改完成目标所必需的 POM / 构建配置。若根 POM 管理插件版本、子模块只继承配置，应沿用既有管理方式，避免重复声明。

## 4. 实施步骤

1. **确认基线：**执行 `java -version`、`mvn -version`、`git status --short --branch`，并生成 POM/依赖/插件扫描摘要。
2. **核实 Mockito：**通过 `mvn dependency:tree -Dincludes=org.mockito:mockito-core,org.mockito:mockito-inline` 和有效 POM 确认实际版本及来源；确认 Inline Mock Maker 当前启用方式。
3. **形成修改计划：**列出将修改的 POM、修改层级、现有 `argLine` 合并策略、Surefire/Failsafe 是否适用。先检查是否有 JaCoCo 或其他插件占用 `argLine`。
4. **最小配置变更：**按第 3.3 节设置 Mockito JAR 路径属性和 `-javaagent`；只对实际执行测试的模块生效。保留 Inline Mock Maker 配置。
5. **核对有效配置：**运行 `mvn help:effective-pom`，确认 Agent 路径解析为实际 `mockito-core` JAR，且没有覆盖其他 JVM 参数；必要时检查 Maven Debug 输出中的 fork JVM 命令行。
6. **执行测试：**必须真实执行相关单元测试及项目已有的集成测试。完整日志保存在 `logs\D23`；模型默认只读取测试汇总、失败用例、首个根因异常和相关堆栈片段。
7. **审查差异：**确认改动仅限必要 POM/构建配置及本轮记录/文档，不修改业务代码和测试逻辑，不升级无关依赖。
8. **更新证据记录并交付：**按第 5 节形成结构化状态记录及两份正式文档。

## 5. 测试与验收

### 必须执行

- `mvn help:effective-pom`：验证有效插件配置、`argLine` 合并和 Agent 路径；
- `mvn dependency:tree -Dincludes=org.mockito:mockito-core,org.mockito:mockito-inline`：验证 Mockito 实际版本与依赖来源；
- 项目现有的相关单元测试；
- 若存在 Failsafe，执行现有集成测试生命周期（按项目配置运行 `mvn verify` 或对应模块命令）；
- 检查 fork JVM 的实际启动参数，证明 `-javaagent` 指向本次测试所用的 `mockito-core` JAR；
- 检查现有 Inline Mock Maker 配置/能力未被移除，并确认相关测试仍可运行。

不得只凭警告消失判断成功；应以有效 POM、依赖解析结果、fork JVM 命令行和测试结果共同证明配置正确。测试失败必须保留原始结果并诊断，不得跳过或伪造通过。

### 验收矩阵

| 编号 | 验收标准 | 必须证据 |
|---|---|---|
| D23-AC01 | Mockito 依赖版本未被无故改变 | 依赖树前后对比 |
| D23-AC02 | Inline Mock Maker 保留 | 扩展配置检查及相关测试结果 |
| D23-AC03 | Surefire 测试 JVM 显式加载 Mockito Agent | 有效 POM + fork JVM 参数摘要 |
| D23-AC04 | 原有 `argLine` 参数未丢失 | 有效 POM 前后对比 |
| D23-AC05 | 若存在 Failsafe，其集成测试 JVM 独立配置正确 | Failsafe 有效配置及集成测试结果 |
| D23-AC06 | 相关单元/集成测试实际执行 | Surefire/Failsafe 测试汇总 |
| D23-AC07 | 未修改业务源码和现有测试逻辑 | Git Diff 文件清单 |
| D23-AC08 | 无无关依赖升级、无警告屏蔽参数 | POM 差异与敏感/参数检查摘要 |

## 6. 结构化迭代记录

在每个功能切片完成时更新：

文件：`docs\evidence\D23\D23-iteration-state.md`

```markdown
# D23 迭代状态

## 需求编号
D23-R01 ~ D23-R05

## 修改文件
- 实际修改的 POM / 构建配置文件

## 设计决定
- Mockito 解析版本及来源：
- Agent JAR 路径解析方式：
- Surefire argLine 合并方式：
- Failsafe 是否存在及处理方式：
- Inline Mock Maker 保留方式：

## 测试
- 命令：
- 结果：通过 / 失败 / 未执行
- 测试数量（仅填写真实报告数据）：

## 证据
- 有效 POM 摘要：
- 依赖树摘要：
- fork JVM Agent 参数摘要：
- 测试报告路径：
- Git Diff 摘要：

## 遗留问题
- 无 / 具体说明
```

## 7. 日志 与 Agent 约束

- 本轮只将 POM 摘要、必要 XML 片段、有效配置摘要和测试摘要交给模型；不传完整构建日志，不重复输出完整 POM。
- 完整日志保存于 `logs\D23`；脚本、扫描报告和临时文本保存于 `docs\temp_docs\D23`；证据保存于 `docs\evidence\D23`。
- 同一根因连续两次修复仍失败，立即停止重复尝试，输出诊断记录（现象、复现、根因假设、已尝试修改、失败证据、下一步验证），再决定是否继续。
- 临时脚本、文本和日志只归档，不自动删除。

## 8. 两份正式文档

从 `D23-iteration-state.md`、实际 Git Diff、有效 POM/依赖报告及测试证据生成，不依赖历史对话回忆，不复制原项目架构说明。

1. `docs\proj_docs\D23-项目迭代全量信息与操作-Mockito显式JavaAgent.md`
2. `docs\proj_docs\D23-技术说明与实践指南-Mockito Inline Mock Maker与Surefire配置.md`

第一份记录本次目标、现状、修改文件、配置决策、执行步骤、测试及交付结果；第二份解释 Java 21 动态 Agent 背景、Inline Mock Maker、Mockito `mockito-core` Agent、Maven `dependency:properties`、Surefire/Failsafe `argLine`、IDEA 与命令行验证方式及常见故障。所有结论必须与本项目实际配置和测试证据一致。

## 9. 最终交付报告

精简输出：

1. **现状摘要：** Mockito 版本、Inline Mock Maker 方式、Surefire/Failsafe 是否存在、已有 `argLine` 及关键风险。
2. **修改清单：** 文件路径与每项修改目的。
3. **配置结果：** Agent 实际 JAR 路径、Surefire/Failsafe 应用范围及参数合并情况。
4. **测试结果：** 真实命令、通过/失败/未执行摘要及完整日志路径。
5. **验收矩阵：** D23-AC01 至 D23-AC08 状态和证据路径。
6. **Git 结果：** 分支、Diff 摘要、是否提交及真实 Commit ID（未提交则明确说明）。
7. **文档与遗留问题：** 两份文档路径、临时文件归档位置和未解决事项。

**启动要求：**先检查给定根目录和 POM；若找不到用户提及的附加指令文件，记录“未找到/未读取”，不得假称已读取。完成基线与修改计划后再修改。未经授权不得提交或推送。不得声称未执行的测试已通过。

## 10. 官方依据

- Mockito | Explicitly setting up instrumentation for inline mocking (Java 21+) | 显式配置 Inline Mocking 的 Instrumentation（Java 21+）：[https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3)
- Apache Maven Dependency Plugin – dependency:properties | Maven 依赖插件 properties 目标：[https://maven.apache.org/plugins/maven-dependency-plugin/properties-mojo.html](https://maven.apache.org/plugins/maven-dependency-plugin/properties-mojo.html)
- Maven Surefire Plugin – argLine | Maven Surefire 测试 JVM 参数配置：[https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html)
- Maven Surefire Plugin – Fork Options and Parallel Test Execution | Surefire Fork JVM 与并行测试：[https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)
- Maven Failsafe Plugin – integration-test | Maven Failsafe 集成测试 JVM 参数：[https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html)
