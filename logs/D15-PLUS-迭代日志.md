# D15-PLUS 迭代日志：MySQL 与 Redis 替换内存存储

- 模块：`D15_PLUS/D15_FOPF_PLUS`（由 `D15/D15_FOPF` 完整复制）
- 基线：JDK 21 / Maven 3.9.16 / Spring Boot 3.5.16 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / MySQL 9.7.0-commercial / Redis 6.2.12
- 日期：2026-10-28
- 说明：本日志按时间顺序记录实际工程动作、失败与处置，测试与验证结果均来自真实命令输出；失败轮次的原始输出保留在 `docs/temp_docs/`，未删除、未粉饰。

---

## 阶段 A 骨架扫描与差异核对

- 全量哈希比对 D15 与 D15_PLUS：两模块仅 `pom.xml`、`src/main/resources/application.yml` 内容不同，其余源码、测试、前端、种子 JSON 完全一致，确认“只加依赖与连接配置”的复制前提属实。
- 依赖盘点：新增 mybatis-plus-spring-boot3-starter 3.5.17（版本由父 POM dependencyManagement 锁定）、spring-boot-starter-data-redis（Lettuce）、mysql-connector-j 8.0.33、spring-security-crypto（当前无代码引用，本次不碰）。
- 本地仓库 `D:\SoftwareInstall\Apache\repository` 实测存在 MyBatis-Plus 3.5.17 构件，支持离线构建。
- 中间件实测：MySQL970 服务监听 3306，root/123456 可登录，`ai_db` 存在且含 8 张其他模块旧表、无 prompt 表；Redis 6.2.12 监听 6379 无密码，PING 返回 PONG。
- 定位两处内存权威存储：`InMemoryPromptTemplateRepository`（ConcurrentHashMap + @PostConstruct 加载 6 模板种子）与 `ConversationMemory`（500 会话 LRU、单会话 20 条 FIFO）；确认上层仅依赖 Repository 接口 4 方法与 ConversationMemory 的 5 个公开方法，替换面可控。
- 过程记录：`docs/temp_docs/D15P-scan-and-design.txt` 及 D15P-*.txt 系列探针输出。

## 阶段 B 存储设计

- 模板：MySQL 权威 + Redis 详情 cache-aside；会话：Redis 滑动 TTL 短期存储、不进 MySQL；种子：ApplicationRunner 幂等插入、已存在跳过永不覆盖。
- Key 规范：`d15f:prompt:tpl:{id}`、`:null` 空标记、`d15f:conv:{sessionId}`；正常 TTL 1800s 滑动续期、空标记 60s。
- 序列化：StringRedisTemplate + Jackson JSON，不用 JDK 默认序列化；凭据环境变量化并保留本机开发默认值。
- 设计全文：`docs/temp_docs/D15P-scan-and-design.txt`。

## 阶段 C 编码

### C1 模板持久化

- 产出 DDL `resources/db/schema-prompt-template.sql`，两次实际执行验证 `IF NOT EXISTS` 幂等，DESC 核对结构通过（证据 D15P-ddl-exec3.txt）。
- 新增 PO（autoResultMap + JacksonTypeHandler 映射 JSON 列）、Mapper、MybatisPromptTemplateRepository（三态读、空标记、回源、写后失效、Redis 异常降级/告警）、PromptSeedInitializer（事务、计数、失败阻止启动）、PromptCacheProperties。
- 旧 InMemoryPromptTemplateRepository 摘 @Repository、标 @Deprecated 保留文件；启动类加 @ConfigurationPropertiesScan；GlobalExceptionHandler 补 DataIntegrityViolationException → 4001。

### C2 会话 Redis 化

- 新增 RedisSessionPayload 显式序列化 record；ConversationMemory 全量重写，公开方法签名不变，20 条 FIFO 常量保留，size() 用 RedisCallback + SCAN 游标。
- 新增 ConversationRedisProperties；application.yml 补数据源、Redis、MyBatis-Plus、缓存与会话参数。

### C3 测试改造

- 新增 PersistenceCacheIntegrationTest（2 用例）、RedisConversationMemoryTest（3 用例）。
- 首轮 mvn test 失败：YAML 中 spring.ai 块被顶出 spring 层级导致 DeepSeek api-key 未绑定、上下文启动失败；修正缩进后第二轮 68 用例全部通过。
- 测试后检查发现 Web/Stream 用例在 MySQL 留软删行、Redis 留会话 Key，抽 AbstractPersistenceCleanupTest 统一清场并把缓存清理扩大到整前缀；第四轮测试后库表仅 6 个种子、Redis `d15f:*` 零残留。

## 阶段 D 编译、测试与真机验证

- `mvn compile` 退出码 0（D15P-compile-2.txt）；踩 PowerShell 对 `-Dmaven.repo.local` 错误分词坑，去掉该参数改由 settings.xml 生效。
- 最终 `mvn test`：Tests run: 68, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS（证据 31）。
- `mvn package -DskipTests`：BUILD SUCCESS，fat jar 56,350,743 字节（证据 32）。
- 真机两轮启动（AI_MODE=mock）：
  - 空库首启“新增 6 条/跳过 0”，二启“新增 0/跳过 6”；
  - 直改库种子名后重启，权威库改名保留、初始化器不覆盖；期间观测到绕过应用改库的缓存陈旧现象，删缓存键后 API 回源读到新值，全过程留证（22-27）；
  - 缓存回源/滑动 TTL/空标记 60s/更新失效/删缓存恢复/逻辑删除 deleted=1 全部实测；
  - 同步双轮对话历史条数正确、会话 JSON 落 Redis TTL=1800，重启后复用 sessionId 历史=4；SSE start/delta/complete 帧完整；
  - 验证结束停止进程、还原种子名、物理清理演示行与 Redis 键，最终表 6 行干净种子、Redis 零残留（28-30）。
- 证据目录：`docs/evidence/D15-plus/`（34 个文件 + README 索引）。

## 阶段 E 文档与交付

- 交付两份正式文档：`D15-PLUS-01 ...-项目迭代全量记录.md`、`D15-PLUS-02 ...-技术说明与实践指南.md`。
- 临时过程文件统一留在 `docs/temp_docs/`，按要求不自动删除。
- 未执行任何 git commit/push；git 状态仅做核对汇总。

## 遗留与可信边界

- DeepSeek 真实链路无有效 Key，未联网实证，1 个冒烟用例保持跳过。
- Redis 故障降级分支未做停机故障注入真机演练。
- 会话 TTL 到期即失为设计取舍；测试依赖本机 MySQL/Redis 在线。
- 管理接口无鉴权、缓存一致性为单实例低频写模型，后续演进见技术指南第 8 章。
