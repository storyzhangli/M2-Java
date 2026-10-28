# D15-01 Prompt 模板管理与客服/编程双角色对话：项目迭代全量记录

> 文档定位：本文只记录本模块**本次迭代实际交付的全部内容与可复现操作**，所有结论以仓库当前代码与真机验证证据为准；未验证项在第 9 章显式标注，不把计划写成已交付。
>
> 基线环境：JDK 21.0.7、Maven 3.9.16、Spring Boot 3.5.16、Spring AI 1.1.8、Tomcat + Spring MVC（Servlet 栈）、本机 mock 模式零外网。
>
> 模块路径：`D15/D15_FOPF`（由 D14 SSE 骨架复制演进，包名 `com.zl.d15`）。

---

## 1. 迭代背景与目标

### 1.1 背景

D14 已交付单轮/流式（SSE，Server-Sent Events，服务器发送事件）聊天能力，但系统提示词（System Prompt）散落在代码里、无法管理，且所有对话共用同一上下文，没有“角色”概念，也没有多轮会话隔离。

### 1.2 本次目标（任务书两项功能）

1. **Prompt 模板管理**：提供不同场景的 Prompt 模板，支持列表、详情、创建、编辑、启停、逻辑删除、变量声明与渲染校验，内置 6 个场景种子，其中客服、编程两个角色必须可以实际对话验证。
2. **双角色多轮对话**：客服（customer_service）、编程（programming）两个角色，服务端校验角色与模板状态，系统提示词、历史消息、用户消息三段分离组装，切换角色会话隔离，同步与 SSE 两条链路都可用。

### 1.3 明确不做的事（范围边界）

- 不引入数据库、Redis、安全框架：模块 `pom.xml` 只有 web/validation/aop/spring-ai/lombok/test 依赖，持久化采用“classpath 种子 JSON + 内存仓库”的最小可维护方案，Repository 接口化便于日后替换为 DB。
- 不升级任何依赖版本、不改动既有 SSE 双层超时与 D14 前端 SSE 解析器。
- 不做登录鉴权（当前模块本就无鉴权体系），管理接口无身份控制这一风险在第 9 章显式登记。
- 不执行任何 git commit/push。

---

## 2. 交付物总览

### 2.1 新增文件（19 个 Java 主源码 + 5 个测试类 + 2 个前端文件 + 1 个种子文件，共 22 个主资源/前端文件与 5 个测试类）

| 分类 | 文件（相对 `src/main/java/com/zl/d15/`） | 职责 |
| --- | --- | --- |
| 模板领域模型 | `prompt/model/PromptScenes.java` | 6 个场景常量、角色场景集合与全场景集合 |
| 模板领域模型 | `prompt/model/TemplateStatus.java` | 模板状态枚举 ENABLED/DISABLED |
| 模板领域模型 | `prompt/model/TemplateVariable.java` | 变量声明 record（name/required/description/defaultValue） |
| 模板领域模型 | `prompt/model/PromptTemplateEntity.java` | 模板可变实体（含 version/status/deleted/时间戳） |
| 模板 DTO | `prompt/dto/PromptTemplateUpsertRequest.java` | 创建/更新请求体，含 Bean Validation |
| 模板 DTO | `prompt/dto/TemplateStatusUpdateRequest.java` | 启停请求体 |
| 模板 DTO | `prompt/dto/PromptTemplateSummary.java` | 列表视图（不含正文） |
| 模板 DTO | `prompt/dto/PromptTemplateView.java` | 详情视图（含正文与变量） |
| 模板仓储 | `prompt/PromptTemplateRepository.java` | 仓储接口（面向接口，日后可换 DB 实现） |
| 模板仓储 | `prompt/InMemoryPromptTemplateRepository.java` | ConcurrentHashMap 内存实现，`@PostConstruct` 加载种子 |
| 模板校验 | `prompt/PromptTemplateValidator.java` | 占位符一致性、裸大括号预检、ST4 桩值试渲染 |
| 模板服务 | `prompt/PromptTemplateService.java` | 生命周期、启停、逻辑删除、角色模板解析与渲染 |
| 模板接口 | `prompt/PromptTemplateController.java` | `/prompts` 全套管理接口与 `/roles` |
| 会话模型 | `conversation/model/ConversationMessage.java` | 单条消息 record（user/assistant） |
| 会话模型 | `conversation/model/ConversationSession.java` | 会话聚合根，绑定场景/模板版本，FIFO 截断 |
| 会话存储 | `conversation/ConversationMemory.java` | 访问有序 LinkedHashMap，上限 500 会话、单会话 20 条 |
| 会话编排 | `conversation/RoleConversationService.java` | 模板校验→渲染→会话绑定→历史组装→失败回滚 |
| 模型入参 | `service/ChatInvocation.java` | 系统提示词+历史+当前用户消息的组合 record |
| 角色响应 | `dto/RoleChatReplyResponse.java` | 同步角色响应（含 sessionId 与模板溯源） |
| 种子资源 | `src/main/resources/prompt-templates/seed-templates.json` | 6 个内置中文模板 |
| 前端 | `src/main/resources/static/templates.html`、`static/js/templates.js` | 模板管理页（列表/筛选/详情/新建/编辑/启停/删除） |

新增测试（相对 `src/test/java/com/zl/d15/springai/`）：

| 测试类 | 用例数 | 覆盖点 |
| --- | --- | --- |
| `prompt/PromptTemplateServiceTest.java` | 9 | 种子加载、全种子可渲染、过滤、详情缺失、生命周期、未声明占位符、ST4 转义渲染、必填变量/未知入参键、停用与非角色场景解析 |
| `conversation/RoleConversationServiceTest.java` | 5 | 系统/用户消息分离、历史累积、跨角色拒绝、停用拒绝、失败回滚、非法变量 |
| `web/PromptTemplateControllerWebTest.java` | 4 | 角色清单、场景过滤、非法创建 4001、管理端完整生命周期 |
| `web/RoleChatControllerTest.java` | 4 | 同步角色响应溯源、双轮历史、非法 scene/变量、旧链路契约不变 |
| `stream/RoleChatStreamIntegrationTest.java` | 4 | SSE start 帧溯源、双轮流式历史、非法角色 JSON 回落、管理静态页 |

合计新增 **26 个测试方法**；与 D14 存量 37 个方法相加，全量共 63 个测试方法。

### 2.2 修改的既有文件（12 个）

| 文件 | 修改要点 |
| --- | --- |
| `service/AiChatService.java` | 新增 `chat(ChatInvocation)`、`streamChat(ChatInvocation)` 两个 default 重载，旧签名不动 |
| `service/MockAiChatService.java` | 覆写角色重载，输出确定性回显（系统提示词长度、历史条数、首行），零外网 |
| `service/DeepSeekAiChatService.java` | 覆写角色重载，按 SystemMessage→历史 User/AssistantMessage→当前 UserMessage 组装 |
| `dto/ChatRequest.java` | 新增可选字段 scene/sessionId/variables，不带 scene 时行为与旧版本完全一致 |
| `dto/SsePayloads.java` | Start 增加 4 个可空溯源字段，`@JsonInclude(NON_NULL)` 保证旧帧 JSON 形态不变，保留旧构造器 |
| `controller/ChatController.java` | 注入角色编排服务，scene 非空走角色链路，返回类型放宽为 `Result<Object>` |
| `controller/ChatStreamController.java` | 角色链路接线：start 溯源帧、助手文本累积落库、失败/超时/断连回滚；双层超时逻辑原样保留 |
| `logging/ControllerAccessLogAspect.java` | **缺陷修复**：切点从遗留的 `com.zl.d14.controller` 修正为 `com.zl.d15.controller`（详见第 7 章） |
| `static/index.html`、`static/js/app.js`、`static/css/app.css` | 角色选择器、会话标识与重置、模板管理入口；新增管理页样式；全程 textContent 渲染 |
| `test/.../stream/ChatStreamIntegrationTest.java` | 仅同步一处静态页标题断言（D14→D15 页面标题变更），断言语义不变 |

---

## 3. 数据模型与内置种子

### 3.1 模板实体字段

| 字段 | 说明 |
| --- | --- |
| templateId | 稳定唯一标识，创建后不可改，正则 `[A-Za-z][A-Za-z0-9_-]{0,63}` |
| name / scene / description | 展示名、场景标识、用途说明 |
| content | 模板正文，上限 8000 字符；占位符语法 `{变量名}`，字面大括号必须写作 `\{` `\}` |
| variables | 变量声明列表：name（字母开头）、required、description、defaultValue |
| version | 版本号，创建为 1，每次更新自增；进行中会话绑定快照版本，模板升级不影响旧会话 |
| status | ENABLED / DISABLED |
| deleted | 逻辑删除标志，删除后列表/详情不可见、不能发起新对话 |
| createdAt / updatedAt | 时间戳 |

### 3.2 六个内置种子场景

| scene | 中文名 | 变量 | 是否可对话角色 |
| --- | --- | --- | --- |
| customer_service | 客服对话助手 | business_context（可选，默认“无额外业务背景”） | 是 |
| programming | 编程技术助手 | tech_stack（可选，默认“未明确指定，需要在回答中先确认”） | 是 |
| technical_explanation | 技术知识解释 | audience（可选，默认“有一定编程基础的学习者”） | 否 |
| code_review | 代码审查 | code_language（可选，默认 Java），正文含 ST4 转义 JSON 示例 | 否 |
| summarization | 文本总结 | source_text（**必填**）、max_words（可选，默认 300） | 否 |
| information_extraction | 结构化信息提取 | schema_hint（**必填**）、output_language（可选，默认 中文） | 否 |

### 3.3 会话容量规则

- 最多保留 500 个会话，超出后按最久未访问淘汰（LinkedHashMap access-order）。
- 单会话最多 20 条消息（用户+助手合计），超出 FIFO 丢弃最早消息。
- sessionId 为空时服务端生成 32 位 UUID（去横线）；同一 sessionId 只能绑定一个角色，换角色必须新会话。

---

## 4. HTTP 接口清单（基址 `/api/v1/ai`）

统一返回体 `Result`：HTTP 状态恒 200，业务码 `code` 区分结果（200 成功、4001 参数/业务错误、5000 系统错误、5001 配置错误、5002 上游不可用）。

### 4.1 模板管理与角色

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/roles` | 启用中的可对话角色清单（2 个） |
| GET | `/prompts?scene=&status=&keyword=` | 模板列表，三个查询参数均可空 |
| GET | `/prompts/{templateId}` | 模板详情 |
| POST | `/prompts` | 创建模板 |
| PUT | `/prompts/{templateId}` | 更新模板（版本自增，templateId 不可改） |
| PATCH | `/prompts/{templateId}/status` | 启用/停用，body `{"status":"ENABLED"}` |
| DELETE | `/prompts/{templateId}` | 逻辑删除 |

### 4.2 对话（复用 D14 路径，不新增 URL）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat` | 同步对话；body 带 scene 走角色链路，不带 scene 走旧链路 |
| POST | `/chat/stream` | SSE 流式对话，角色判定规则同上 |
| GET | `/health` | 模式自检，不触发模型调用 |

角色请求体在 D14 的 `message` 之外新增三个**可选**字段：

```json
{
  "message": "我买的设备三天还没发货，怎么处理？",
  "scene": "customer_service",
  "sessionId": "首轮不传，服务端生成后回传",
  "variables": { "business_context": "演示电商售后场景" }
}
```

SSE 事件仍为 start/delta/complete/error 四类；角色链路的 start 帧额外携带 `sessionId、scene、templateId、templateVersion`，旧链路这些字段为 null 且不出现在 JSON 中。

---

## 5. 本地操作手册

### 5.1 编译与测试

```powershell
# Maven 与仓库使用长期记忆中的固定路径
Set-Location 'E:\AI\project\AI-Learning-one\M2-Java\D15\D15_FOPF'
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' compile
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' test

# 前端 SSE 解析器回归（Node v22，内置 node:test）
Set-Location '.\src\test\node'
node --test
```

### 5.2 打包与 mock 模式启动

```powershell
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' package -DskipTests
$env:AI_MODE='mock'   # 默认就是 mock，显式设置便于阅读
& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' -jar '.\target\D15_FOPF-1.0-SNAPSHOT.jar'
```

启动后访问：

- 对话页：`http://127.0.0.1:8080/index.html`
- 模板管理页：`http://127.0.0.1:8080/templates.html`

### 5.3 接口快速验证（真机实测命令）

```powershell
# 角色清单
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/ai/roles'
# 同步角色对话（首轮不传 sessionId）
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/ai/chat' -Method Post `
  -ContentType 'application/json;charset=UTF-8' `
  -Body '{"message":"测试问题","scene":"programming"}'
# SSE 角色流式（注意请求 Content-Type 仍是 application/json）
Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/ai/chat/stream' -Method Post `
  -ContentType 'application/json;charset=UTF-8' `
  -Body '{"message":"流式测试","scene":"customer_service"}'
```

完整可复现采集脚本：`docs/temp_docs/D15-collect-evidence.ps1`（temp_docs 不自动删除，由用户自行管理）。

### 5.4 前端操作路径

1. 打开对话页，顶部下拉框选择“客服对话助手/编程技术助手/通用模式”。
2. 切换角色会自动重置会话；同一角色多轮对话沿用服务端回传的 sessionId。
3. 点击“新对话”清空页面并重新生成会话；点击“模板管理”进入管理页，可筛选、新建、编辑、启停、逻辑删除模板。

---

## 6. 验证结果（真实执行，非推断）

### 6.1 Maven 全量测试

- 命令：`mvn test`
- 结果：**Tests run: 63, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS**
- 唯一跳过项：`DeepSeekLiveSmokeTest`（无 `DEEPSEEK_API_KEY` 的联网冒烟，跳过属预期，D14 起即如此）。
- 原始输出：`docs/evidence/D15-fopf/08-mvn-test-raw.txt`。

### 6.2 Node 解析器回归

- 命令：`node --test`（`src/test/node/sse-parser.test.js`）
- 结果：**9 用例全部通过**，覆盖 LF/CRLF/CR 三种行结束、跨读取块、UTF-8 中文跨字节边界。
- 原始输出：`docs/evidence/D15-fopf/09-node-sse-parser-raw.txt`。

### 6.3 真机 mock 链路（可执行 jar + 真实 HTTP）

| 验证点 | 结果 | 证据 |
| --- | --- | --- |
| 健康检查与角色清单 | 200，角色恰好 2 个 | `01-health-roles.txt` |
| 6 个种子模板加载与详情 | 列表 6 条，详情含变量声明 | `02-prompts-list-detail.txt` |
| 同步双轮角色对话 | 首轮生成 32 位 sessionId，第二轮回显“历史消息条数=2” | `03-sync-role-chat.txt` |
| SSE 双轮流式 | start 帧含模板溯源，delta seq 从 0 连续，第二轮历史=2 | `04-sse-role-stream.txt` |
| 模板管理生命周期 | 合法模板创建成功、未声明占位符 4001、启停/逻辑删除闭环 | `05-template-admin.txt` |
| 错误路径 | 非法 scene、未声明变量均返回 4001 且中文原因明确 | `06-error-paths.txt` |
| 静态页面 | index.html、templates.html 均 HTTP 200 | `07-static-pages.txt` |
| AOP 切面修复 | 运行日志出现 17 条 CONTROLLER_ACCESS，种子加载日志 1 条 | `10-app-startup-aop.txt` |

证据索引：`docs/evidence/D15-fopf/README.md`。证据目录已用 `git check-ignore` 确认未被忽略，可纳入版本管理。

---

## 7. 过程中发现并修复的既有缺陷

### 7.1 AOP 切点包名遗留（D14→D15 复制残留）

- 现象：首轮 `mvn test` 有 2 个既有日志测试失败，缺少 `CONTROLLER_ACCESS` 事件。
- 根因：`ControllerAccessLogAspect` 切点硬编码为 `execution(public * com.zl.d14.controller..*.*(..))`，而 D15 控制器在 `com.zl.d15.controller`，切面从未生效。
- 修复：切点改为 `com.zl.d15.controller`，并在注释中登记该复制残留风险；未改动任何日志业务逻辑。
- 验证：修复后全量 63 用例通过，真机日志确认切面事件输出。

### 7.2 首轮测试失败的方法论说明

首轮测试失败不是新功能代码导致，而是骨架复制时的环境性残留；按“先证据、再定位根因、最小修改、回归验证”处理，未绕过任何失败用例。

---

## 8. 配置项与环境变量

本次未新增配置文件，全部复用 D13/D14 既有配置（`application.yml`）：

| 配置 | 默认值 | 环境变量 | 本次关系 |
| --- | --- | --- | --- |
| `app.ai.mode` | mock | `AI_MODE` | mock 下角色对话走确定性回显，不触外网 |
| `app.ai.max-message-length` | 2000 | `AI_CHAT_MAX_LENGTH` | 角色消息同样受此上限约束 |
| `app.ai.stream-timeout-ms` | 60000 | `AI_STREAM_TIMEOUT_MS` | 角色 SSE 沿用双层超时 |
| `spring.ai.deepseek.api-key` | 本地无效占位串 | `DEEPSEEK_API_KEY` | 真实角色对话需自行注入，密钥不入库 |
| `app.access-log.*` | true/2048 等 | `ACCESS_LOG_ENABLED` 等 | 修复切点后对新控制器同样生效 |

---

## 9. 已知限制与可信边界

1. **内存持久化**：模板改动与会话只存在于 JVM 内存，应用重启后模板恢复为种子数据、会话清空。这是无 DB 依赖下的明确取舍；Repository 已接口化，后续接 MySQL 只需新增实现。
2. **无鉴权**：模板写接口与对话接口没有身份认证/授权，不可直接暴露到非可信网络；上线前需接入统一鉴权。
3. **DeepSeek 真实链路未联网实证**：本机未注入有效 API Key，真实厂商调用未执行；DeepSeek 实现的消息组装顺序经代码审查与 mock 同构链路验证，联网效果不在本次证据范围内。
4. **内存容量是单机口径**：500 会话/20 条的上限只适用于单实例；多实例部署需要共享存储（Redis/DB）才能支持会话粘滞之外的横向扩展。
5. **应用名遗留**：`spring.application.name` 仍为 `D01_SpringAI_init`（D14 即判定不在迭代范围），本次未改动，后续可单独立项修正。

---

## 10. 记忆简语与层级树

**记忆简语**：模板先声明后渲染、角色服务端说了算；系统历史用户三段分、换角色必须换会话；内存重启回种子、失败回滚不留污。

```text
D15_FOPF
├─ prompt 模板域
│  ├─ model：实体/状态/变量/场景常量
│  ├─ dto：请求体 + 列表/详情视图
│  ├─ repository：接口 + ConcurrentHashMap 内存实现（种子 JSON）
│  ├─ validator：占位符一致性 + 裸括号预检 + ST4 试渲染
│  ├─ service：生命周期/启停/逻辑删/角色解析/渲染
│  └─ controller：/prompts 全套 + /roles
├─ conversation 会话域
│  ├─ model：Message + Session（场景绑定/FIFO）
│  ├─ memory：500 会话 LRU + 单会话 20 条
│  └─ RoleConversationService：prepare/成功落库/失败回滚
├─ service 模型适配
│  ├─ ChatInvocation：systemPrompt + history + userMessage
│  ├─ Mock：确定性回显
│  └─ DeepSeek：System/Assistant/User Message 组装
└─ controller 入口
   ├─ /chat：scene 非空走角色同步链路
   └─ /chat/stream：start 溯源帧 + 累积落库 + 异常回滚
```

---

## 11. 参考资料

1. 《Chat Client API | 聊天客户端 API 参考》：https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html
2. 《Prompts | 提示词与 PromptTemplate 参考》：https://docs.spring.io/spring-ai/reference/1.1/api/prompt.html
3. 《AI Concepts | Spring AI 核心概念（StringTemplate 模板）》：https://docs.spring.io/spring-ai/reference/1.1/concepts.html
4. 《Parsing an event stream | 事件流解析标准（WHATWG HTML）》：https://html.spec.whatwg.org/multipage/server-sent-events.html
