# D15-PLUS-01 MySQL/Redis 替换内存存储：项目迭代全量记录

> 文档定位：本文只记录本模块**本次迭代实际交付的全部内容与可复现操作**，所有结论以仓库当前代码、真实命令输出与 `docs/evidence/D15-plus/` 证据为准；未验证项在第 8 章显式标注，不把计划写成已交付。
>
> 基线环境：JDK 21、Maven 3.9.16（主路径 `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16`，本地仓库 `D:\SoftwareInstall\Apache\repository`）、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17、MySQL 9.7.0-commercial、Redis 6.2.12，本机 mock 模式零外网。
>
> 模块路径：`D15_PLUS/D15_FOPF_PLUS`（由 `D15/D15_FOPF` 完整复制，仅 `pom.xml` 与 `application.yml` 预置 MySQL/Redis 依赖与连接配置，包名保持 `com.zl.d15`）。

---

## 1. 迭代背景与目标

### 1.1 背景

D15 已交付 Prompt 模板管理与客服/编程双角色多轮对话，但两处权威状态都在 JVM 内存：

- 模板主存储是 `ConcurrentHashMap`，启动时无条件加载 classpath 种子 JSON，应用重启后运行期的增删改全部丢失；
- 多轮会话存放在访问有序 `LinkedHashMap`（500 会话 LRU、单会话 20 条 FIFO），进程重启会话即失，且容量受单机堆内存约束。

D15_PLUS 在不改变业务接口与前端契约的前提下，把持久化职责迁移到本机已有的 MySQL 与 Redis。

### 1.2 本次目标（任务书核心要求）

1. **MySQL 替换模板内存存储**：模板权威数据落库（MyBatis-Plus 3.5.17），种子 JSON 改为启动期幂等初始化，**已存在的键一律跳过，永不覆盖库内修改**。
2. **Redis 承担两类职责**：模板详情 cache-aside（旁路缓存）与多轮会话短期存储；统一 Key 前缀、TTL 与 JSON 序列化规范。
3. **业务零感知切换**：`PromptTemplateService` 仍只依赖 `PromptTemplateRepository` 接口，`RoleConversationService` 依赖的 `ConversationMemory` 公开方法签名不变，控制器、前端与同步/SSE 两条对话链路不动契约。
4. 全量 Maven 测试通过、可执行 jar 真机验证重启持久化与缓存行为，证据独立落盘。

### 1.3 明确不做的事（范围边界）

- 不引入新 ORM、不引入 Spring Cache 注解体系、不引入分布式锁或消息队列；缓存策略用显式代码实现，保持可读可控。
- 不升级任何既有依赖版本，不改 D15 的接口路径、请求响应结构、前端页面与 SSE 协议。
- 会话**不写入 MySQL**：会话天然是短期状态，用 Redis 滑动 TTL 治理，替代旧的“500 会话 LRU 硬上限”，这一容量治理方式的差异在第 3.3 节与第 8 章显式说明。
- 不删除种子 JSON 与 `InMemoryPromptTemplateRepository` 类文件（后者摘除 Bean 身份并标 `@Deprecated` 留档）。
- 不执行任何 git commit/push；密码不写入日志，配置通过环境变量占位。

---

## 2. 交付物总览

### 2.1 新增主源码与资源（9 个）

| 文件（相对 `src/main/`） | 职责 |
| --- | --- |
| `resources/db/schema-prompt-template.sql` | `prompt_template` 建表 DDL，`CREATE TABLE IF NOT EXISTS` 幂等，含唯一键与联合索引 |
| `java/com/zl/d15/prompt/po/PromptTemplatePO.java` | 表映射对象，`variables` JSON 列经 `JacksonTypeHandler` 映射为 `List<TemplateVariable>` |
| `java/com/zl/d15/prompt/mapper/PromptTemplateMapper.java` | `@Mapper` 接口，继承 MyBatis-Plus `BaseMapper<PromptTemplatePO>` |
| `java/com/zl/d15/prompt/MybatisPromptTemplateRepository.java` | 仓储接口的 MySQL+Redis 实现，cache-aside 三态读、空标记、故障降级、写后失效 |
| `java/com/zl/d15/prompt/PromptSeedInitializer.java` | `ApplicationRunner` 幂等种子初始化，按 `template_id` 判存，输出新增/跳过/失败计数 |
| `java/com/zl/d15/config/PromptCacheProperties.java` | `app.prompt.cache.*` 配置绑定（开关、前缀、TTL、空标记 TTL） |
| `java/com/zl/d15/config/ConversationRedisProperties.java` | `app.conversation.redis.*` 配置绑定（会话 Key 前缀、TTL） |
| `java/com/zl/d15/conversation/RedisSessionPayload.java` | 会话 Redis JSON 载荷 record，显式控制序列化结构 |
| `java/com/zl/d15/conversation/ConversationMemory.java`（重写） | LinkedHashMap 实现替换为 Redis 实现，公开方法签名保持不变 |

> 说明：`ConversationMemory.java` 对 D15 而言是“同名重写”，在新增清单中列出以便审查；它同时出现在 2.2 的修改说明中。

### 2.2 修改的既有文件（5 个）

| 文件 | 修改要点 |
| --- | --- |
| `conversation/ConversationMemory.java` | 全部方法体重写为 Redis 读写，`MAX_MESSAGES_PER_SESSION=20` 常量与 FIFO 语义保留；`size()` 用 SCAN 游标统计 |
| `prompt/InMemoryPromptTemplateRepository.java` | 摘除 `@Repository` 与对应 import，类标注 `@Deprecated` 并更新 Javadoc，保留代码作内存方案留档，不再注册 Bean |
| `D15SpringAiApplication.java` | 增加 `@ConfigurationPropertiesScan`，让两个 `@ConfigurationProperties` 类生效 |
| `common/GlobalExceptionHandler.java` | 新增 `DataIntegrityViolationException` 处理，唯一键冲突等归一为业务码 4001，不向前端回显 SQL 细节 |
| `resources/application.yml` | 增加数据源/Redis 环境变量占位、MyBatis-Plus 段、`app.prompt.cache` 与 `app.conversation.redis` 参数；修正编辑过程中的 YAML 层级问题（见 7.1） |

### 2.3 测试变更

| 文件（相对 `src/test/java/com/zl/d15/springai/`） | 类型 | 说明 |
| --- | --- | --- |
| `support/AbstractPersistenceCleanupTest.java` | 新增基类 | `@AfterEach` 统一清理 ut_ 物理行、整前缀模板缓存（含空标记）、全部会话 Key |
| `prompt/PersistenceCacheIntegrationTest.java` | 新增，2 用例 | 种子幂等不覆盖、回源/命中/滑动 TTL/清空恢复/空标记/更新失效 |
| `conversation/RedisConversationMemoryTest.java` | 新增，3 用例 | 绑定 TTL、场景隔离、20 条 FIFO、失败回滚、缺失会话异常、清除幂等 |
| `prompt/PromptTemplateServiceTest.java` | 修改 | 本地清理逻辑替换为继承清理基类 |
| `conversation/RoleConversationServiceTest.java` | 修改 | 同上 |
| `web/PromptTemplateControllerWebTest.java` | 修改 | 继承清理基类，修复其生命周期用例在 MySQL 留下软删行的问题 |
| `web/RoleChatControllerTest.java`、`stream/RoleChatStreamIntegrationTest.java` | 修改 | 继承清理基类，回收会话 Key |

全量结果：**Tests run: 68, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS**。D15 基线为 63 个用例，本次净增 5 个测试方法；唯一跳过项仍是无 API Key 的 `DeepSeekLiveSmokeTest`。

---

## 3. 数据模型与存储职责

### 3.1 `prompt_template` 表结构（DDL 实际执行验证）

| 列 | 类型 | 约束/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK AUTO_INCREMENT | 代理主键，不对外暴露 |
| `template_id` | VARCHAR(64) | NOT NULL，UNIQUE `uk_template_id` | 业务稳定唯一标识，并发写入的最终防重防线 |
| `name` | VARCHAR(100) | NOT NULL | 模板名称 |
| `scene` | VARCHAR(64) | NOT NULL | 场景标识 |
| `description` | VARCHAR(500) | NOT NULL DEFAULT '' | 用途说明 |
| `content` | TEXT | NOT NULL | ST4 模板正文 |
| `variables` | JSON | NOT NULL | 变量声明数组，MyBatis-Plus Jackson TypeHandler 映射 |
| `version` | INT | NOT NULL DEFAULT 1 | 编辑版本号，领域层自增 |
| `status` | VARCHAR(16) | NOT NULL DEFAULT 'ENABLED' | ENABLED/DISABLED，按字符串存储避免枚举序号耦合 |
| `deleted` | TINYINT(1) | NOT NULL DEFAULT 0 | 逻辑删除标记，0 有效 1 删除 |
| `created_at` / `updated_at` | DATETIME | NOT NULL | 显式写入时间戳，不依赖自动填充插件 |

索引：唯一键 `uk_template_id`；联合索引 `idx_scene_status_deleted(scene, status, deleted)` 支撑列表过滤。引擎 InnoDB，字符集 `utf8mb4 / utf8mb4_0900_ai_ci`。

### 3.2 Redis Key 与 TTL 规范（db0）

| Key | Value | TTL | 用途 |
| --- | --- | --- | --- |
| `d15f:prompt:tpl:{templateId}` | 模板实体 JSON 字符串 | 1800 秒，命中滑动续期 | 详情读副本 |
| `d15f:prompt:tpl:{templateId}:null` | 常量 `"1"` | 60 秒 | 空值标记，抑制缓存穿透 |
| `d15f:conv:{sessionId}` | `RedisSessionPayload` JSON | 1800 秒，每次读写滑动续期 | 多轮会话短期存储 |

序列化统一使用 `StringRedisTemplate` + Spring 容器中的 Jackson `ObjectMapper` 写 JSON，不使用 JDK 默认序列化；Key 全部带 `d15f:` 应用与业务域前缀，与同实例其他模块隔离。

### 3.3 容量治理口径的变化（必须显式知晓）

| 维度 | D15 内存版 | D15_PLUS |
| --- | --- | --- |
| 模板存储 | ConcurrentHashMap，重启丢失修改 | MySQL 权威，重启保留，唯一键兜底 |
| 会话容量 | 进程内 500 会话 LRU 硬上限 | 无全局条数上限，按每会话滑动 TTL 30 分钟自动回收 |
| 单会话消息 | 20 条 FIFO | 规则不变，仍为 20 条 FIFO |
| 会话生命周期 | 进程存活期间 | Redis TTL 内跨进程重启可见，TTL 到期不可恢复（设计如此） |

### 3.4 存储职责边界

- **MySQL 是唯一权威存储**：创建、更新、启停、删除先落库；列表查询始终走库（带联合索引），不缓存可变列表。
- **Redis 只做读加速与短期会话**：缓存可以随时清空，读请求回源 MySQL 即可恢复；Redis 故障时模板读降级回源库并告警，写库成功后的缓存失效失败只告警不阻断。
- **种子初始化器不是数据同步器**：只插入缺失键，已存在行（即使内容、状态、删除标记与 JSON 不同）全部跳过。

---

## 4. 配置项与环境变量

`application.yml` 中所有敏感值与环境相关值均支持环境变量覆盖，冒号后仅保留本机开发默认值：

| 配置 | 环境变量（默认值） | 说明 |
| --- | --- | --- |
| `spring.datasource.url` | `MYSQL_URL`（本机 ai_db 地址，含 `useSSL=false&allowPublicKeyRetrieval=true`） | JDBC 连接串 |
| `spring.datasource.username/password` | `MYSQL_USER`(root) / `MYSQL_PASSWORD`(123456) | 数据源凭据 |
| `spring.data.redis.host/port/password/database` | `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_DATABASE` | Redis 连接，默认无密码 db0 |
| `app.prompt.cache.enabled` | `PROMPT_CACHE_ENABLED`(true) | 缓存总开关，关闭后读全回源、写不失效 |
| `app.prompt.cache.ttl-seconds` | `PROMPT_CACHE_TTL_SECONDS`(1800) | 模板副本 TTL |
| `app.prompt.cache.null-ttl-seconds` | `PROMPT_CACHE_NULL_TTL_SECONDS`(60) | 空标记 TTL |
| `app.conversation.redis.ttl-seconds` | `CONV_TTL_SECONDS`(1800) | 会话滑动 TTL |
| `spring.ai.deepseek.api-key` | `DEEPSEEK_API_KEY`（无效占位串） | 沿用 D15，真实 Key 只走环境变量 |

---

## 5. 本地操作手册

### 5.1 建表（幂等，可重复执行）

PowerShell 下 mysql 客户端对 `-h127.0.0.1` 合写、`source` 命令与反斜杠路径均有兼容问题，已实测可靠方式为 stdin 管道：

```powershell
$sql = Get-Content -Raw -Encoding UTF8 '.\src\main\resources\db\schema-prompt-template.sql'
$sql | & 'E:\SoftwareInstall\CyberSecurity\Mysql\mysql-commercial-9.7.0-winx64\bin\mysql.exe' `
  --host=127.0.0.1 --port=3306 -uroot -p123456 -D ai_db --default-character-set=utf8mb4
```

### 5.2 编译与全量测试

测试为完整 `@SpringBootTest` 上下文，运行期间本机 MySQL(3306) 与 Redis(6379) 必须可用：

```powershell
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' test
```

> 注意：settings.xml 已将本地仓库指向 `D:\SoftwareInstall\Apache\repository`，命令行不要再传 `-Dmaven.repo.local=...`，PowerShell 会把该参数错误分词（详见 7.2）。

### 5.3 打包与 mock 模式启动

```powershell
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' package -DskipTests
$env:AI_MODE='mock'
& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' -jar .\target\D15_FOPF_PLUS-1.0-SNAPSHOT.jar
```

### 5.4 真机快速验证命令

```powershell
# 角色清单（仅两个可对话角色）
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/ai/roles'
# 首次读详情触发回源回填，随后用 redis-cli 观察 Key 与 TTL
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/ai/prompts/customer_service'
redis-cli EXISTS d15f:prompt:tpl:customer_service
redis-cli TTL    d15f:prompt:tpl:customer_service
# 同会话两轮对话，观察 d15f:conv:{sessionId}
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/ai/chat' -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"message":"你好","scene":"customer_service"}'
```

---

## 6. 验证结果（真实执行，非推断）

### 6.1 Maven 构建与测试

| 验证项 | 实际结果 | 原始证据 |
| --- | --- | --- |
| `mvn compile` | 退出码 0，无编译错误 | `temp_docs/D15P-compile-2.txt` |
| `mvn test`（最终轮） | 68 用例，0 失败，0 错误，1 跳过，BUILD SUCCESS | `evidence/D15-plus/31-mvn-test-raw.txt` |
| `mvn package -DskipTests` | BUILD SUCCESS，fat jar 56,350,743 字节 | `evidence/D15-plus/32-mvn-package-raw.txt` |
| 测试后数据残留 | 表仅 6 个种子、ut_ 行 0；Redis `d15f:*` 零残留 | 证据 03/05 |

过程中首轮测试曾因 YAML 缩进导致上下文启动失败，修复后四轮测试的完整输出均保留在 `temp_docs/`，未删除失败留痕。

### 6.2 真机行为证据矩阵（jar + 真实 HTTP + mysql/redis-cli）

| 验收点 | 结果 | 证据文件 |
| --- | --- | --- |
| 空库首启种子初始化 | 日志“新增 6 条，跳过 0 条”，6 行落 MySQL | 08、09 |
| 重复启动幂等 | 二启日志“新增 0 条，跳过 6 条” | 23 |
| 人工改库不被覆盖 | 直改种子名后重启，查库改名保留，初始化器跳过 | 22、23、26 |
| 缓存未命中回源回填 | 首读后 Key 存在，TYPE=string，TTL=1799 | 11、12 |
| 滑动续期 | 二次读后 TTL 刷新 | 13 |
| 空值标记防穿透 | 不存在模板写入 `:null`，TTL=60 | 14、15 |
| 更新失效与回源重建 | 更新后 EXISTS=0，再读 TTL=1800 且版本=2 | 16 |
| 清缓存后恢复 | 删 Key 再读，MySQL 回源重建成功 | 17 |
| 逻辑删除 | 删除后 DB 行 `deleted=1`，缓存键清除 | 20、16 |
| 会话 Redis 存储 | 两轮对话历史=2，载荷 JSON 落键，TTL=1800 | 18 |
| 会话跨重启保留 | 重启后复用 sessionId，历史条数=4 | 25 |
| SSE 流式回归 | start/delta/complete 帧完整、seq 连续 | 19 |
| 环境还原 | 应用停止、表恢复 6 个干净种子、Redis 零残留 | 28、29、30 |

### 6.3 一个必须如实记录的现象

绕过应用直接用 SQL 修改种子名后，首次 API 读取仍返回旧名称——原因是 Redis 中旧缓存未被失效（cache-aside 模式下绕过应用写库不会触发缓存删除），直接查权威库可确认改名真实保留；手工删除缓存键后 API 即读到新值。这不是种子覆盖缺陷，证据 24/26/27 完整记录了该差异，技术指南第 4 章给出运维处置规则。

---

## 7. 过程中发现并修复的问题（真实留痕）

### 7.1 YAML 层级错误导致首轮上下文启动失败

在 `spring.data.redis` 与 `spring.ai` 之间插入顶层 `mybatis-plus` 配置块时，`ai:` 块保留了两格缩进却脱离了 `spring:` 父级，导致 `spring.ai.deepseek.api-key` 未绑定，DeepSeek 自动配置抛 “DeepSeek API key must be set”，首轮 `mvn test` 上下文初始化失败。修复方式是把 `ai:` 块移回 `spring:` 下两格缩进、`mybatis-plus:` 保持顶层零缩进，第二轮起全量通过。

### 7.2 PowerShell 对 `-Dmaven.repo.local=...` 的分词问题

PowerShell 将带点号与等号的 Maven 参数错误拆词，报 “No plugin found for prefix '.repo.local=D'”。因两级 settings.xml 已指向目标本地仓库，直接去掉该参数即可，无需换壳。

### 7.3 测试清理缺口

首轮全量测试通过后，实测发现 Web 层生命周期用例在 MySQL 留下软删行、流式/控制器用例在 Redis 留下会话 Key。逐类补 `@AfterEach` 会重复散落，因此抽出 `AbstractPersistenceCleanupTest` 统一清理，并把模板缓存清理范围扩大到整前缀（覆盖任意命名的空标记）；第四轮测试后 Redis 零残留。

### 7.4 mysql 客户端调用坑

`-h127.0.0.1` 合写被解析为主机名 “12”；`-e "source ..."` 在 Windows 反斜杠路径下报未知命令。最终统一使用 `--host=127.0.0.1` 长选项与 stdin 管道执行 SQL。

---

## 8. 已知限制与可信边界

1. **DeepSeek 真实链路未联网实证**：本机未注入有效 `DEEPSEEK_API_KEY`，真实厂商调用保持跳过；本次验证覆盖 mock 链路与持久化层，真实联网结果不在证据范围。
2. **会话 TTL 到期即失**：会话只存 Redis，不做数据库归档；Redis 持久化策略（RDB/AOF）不在本次配置范围内，Redis 数据目录丢失会带走未过期会话，属设计取舍。
3. **Redis 故障降级未做故障注入真机演练**：降级回源分支有显式代码与告警日志，但本次未实际停掉 Redis 验证，仅有代码级保证。
4. **测试依赖本机中间件**：全部测试为真实 MySQL/Redis 集成测试，未使用 Testcontainers 或内嵌库；在未启动两个中间件的环境中测试无法运行。
5. **管理接口仍无鉴权**：沿用 D15 现状，模板管理接口暴露在无安全框架的应用上，生产部署需在网关层补齐认证授权，本次未扩范围。
6. **缓存一致性为单实例低频写模型**：采用“写库后删缓存”，未实现延迟双删或分布式锁；多实例高并发写场景的一致性保障属于后续演进。

---

## 9. 记忆简语与层级树

### 9.1 记忆简语

- **权威在库、加速在缓**：MySQL 定真假，Redis 只做副本，缓存随时可删可重建。
- **种子只插不改**：启动判存，有则跳过，库内人工修改永远不被 JSON 覆盖。
- **空结果短锁、热数据续期**：空标记 60 秒防穿透，正常副本 30 分钟滑动 TTL。
- **写库再删缓存、失败只告警**：数据库成功即权威，缓存残留最多活到 TTL。
- **会话入 Redis、消息仍二十**：会话跨进程靠 Redis，20 条 FIFO 老规矩不变。
- **接口不动、实现替换**：面向 Repository 接口与不变的 ConversationMemory 签名切换存储，业务层零改动。

### 9.2 层级树（竖屏速记）

```text
D15_FOPF_PLUS
├─ 模板主线（权威 MySQL）
│  ├─ DDL：prompt_template（uk_template_id + 联合索引）
│  ├─ PO/Mapper（MyBatis-Plus，JSON 列 TypeHandler）
│  ├─ MybatisPromptTemplateRepository
│  │  ├─ 读：缓存三态（副本/空标记/回源）
│  │  ├─ 写：先落库 → 删缓存
│  │  └─ 故障：Redis 异常降级回源 + 告警
│  └─ PromptSeedInitializer（幂等插种，绝不覆盖）
├─ 会话主线（短期 Redis）
│  ├─ RedisSessionPayload（显式 JSON 契约）
│  ├─ ConversationMemory（签名不变，SCAN 统计）
│  └─ TTL 1800s 滑动 + 单会话 20 条 FIFO
├─ 配置
│  ├─ datasource / redis 环境变量占位
│  ├─ app.prompt.cache.*
│  └─ app.conversation.redis.*
└─ 测试
   ├─ AbstractPersistenceCleanupTest（统一清场）
   ├─ PersistenceCacheIntegrationTest
   └─ RedisConversationMemoryTest（全量 68，跳过 1）
```

---

## 10. 参考资料

- 《Spring Boot Reference Documentation | Spring Boot 参考文档》：https://docs.spring.io/spring-boot/3.5.16/reference/html/
- 《Spring Data Redis Reference Documentation | Spring Data Redis 参考文档》：https://docs.spring.io/spring-data/redis/reference/
- 《MySQL 9.7 Reference Manual | MySQL 9.7 参考手册》：https://dev.mysql.com/doc/refman/9.7/en/
- 《MyBatis-Plus 官方文档》：https://baomidou.com/
- 《StringTemplate 4 Documentation | ST4 模板引擎文档》：https://github.com/antlr/stringtemplate4/blob/master/doc/index.md
