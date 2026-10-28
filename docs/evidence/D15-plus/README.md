# D15_PLUS（MySQL/Redis 替换内存存储）真机验证证据索引

- 验证时间：2026-10-28
- 运行环境：Windows + JDK 21（`D:\SoftwareInstall\JDK\JDK21`）+ Spring Boot 3.5.16 + Spring AI 1.1.8 + MyBatis-Plus 3.5.17
- 中间件：MySQL 9.7.0-commercial（127.0.0.1:3306，库 `ai_db`，表 `prompt_template`）、Redis 6.2.12（127.0.0.1:6379，db0，无密码）
- 被测构件：`D15_FOPF_PLUS-1.0-SNAPSHOT.jar`（56,350,743 字节，`mvn package -DskipTests` 产物）
- 运行模式：`AI_MODE=mock`（离线模拟，零外网调用）；访问基址 `http://127.0.0.1:8080/api/v1/ai`
- 采集方式：PowerShell 调用真实 HTTP 接口、mysql 客户端、redis-cli 落盘，响应内容未手工改写；验证结束后已还原种子数据并停止应用

## 证据清单

| 文件 | 内容 | 关键结论 |
| --- | --- | --- |
| `01-db-prestate.txt` | 验证前 `prompt_template` 表数据 | 表已由 DDL 创建，含测试阶段写入的 6 个种子与 1 条软删测试行 |
| `02-redis-prestate*.txt` | 验证前 Redis db0 中 `d15f:*` 键 | 存在测试残留会话键，验证前先做清理基线 |
| `03-db-posttest.txt` | 全量测试后的表计数 | 仅 6 个种子行，ut_ 测试行 0 条，测试清理基类生效 |
| `04-redis-posttest-keys.txt` / `05-redis-residue.txt` | 两轮测试后的 Redis 残留检查 | 扩大清理范围后 `d15f:*` 零残留 |
| `06-reset-state.txt` | 真机验证前重置表与 Redis、JDK21 路径检查 | 表 0 行、键 0 个、java.exe 路径存在，满足首启演示条件 |
| `07-run1-home.txt` | 首次启动后 GET `/` | 应用启动成功，前端页面正常返回 |
| `08-run1-seed-log.txt` | 首启启动日志 | `新增 6 条，跳过 0 条，失败 0 条`，种子幂等初始化器按预期执行 |
| `09-run1-db-rows.txt` | 首启后查表 | 6 个种子全部落 MySQL，时间戳与启停时刻一致 |
| `10-api-roles.txt` | GET `/roles` | 仅 customer_service、programming 两个可对话角色 |
| `11-api-detail-first-read.txt` / `12-redis-after-first-read.txt` | 首次读详情 + Redis 检查 | 缓存未命中回源 MySQL 后回填，Key 为 string，TTL=1799 |
| `13-redis-second-read.txt` | 二次读详情后的 TTL | 命中缓存并滑动续期 |
| `14-api-missing.txt` / `15-redis-null-marker.txt` | 读不存在模板 | 返回业务错误体；Redis 写入 `:null` 空值标记，TTL=60 |
| `16-lifecycle-cache-eviction.txt` | 模板创建/读/更新/再读/删除全链路 | 更新后缓存键失效（EXISTS=0），再读从 MySQL 重建且版本自增为 2；删除后键清除 |
| `17-cache-recovery.txt` | 手工删除种子缓存键后再读 | 删除前 EXISTS=0，读请求回源 MySQL 后键重建、TTL=1800，证明缓存非权威存储 |
| `18-role-chat-session-redis.txt` | 同会话两轮同步对话 + Redis 载荷 | 第二轮历史消息条数=2；会话以 JSON 存 `d15f:conv:{sessionId}`，TTL=1800 |
| `19-sse-raw-frames.txt` | POST `/chat/stream` 原始 SSE 帧 | start/delta/complete 帧完整，seq 连续，流式链路在新存储下回归通过 |
| `20-db-soft-delete-row.txt` | 逻辑删除后查表 | `deleted=1` 行保留在 MySQL，为逻辑删除而非物理删除 |
| `21-app-stop.txt` | 第一次停止应用 | 进程停止，8080 不再监听 |
| `22-db-manual-rename.txt` | 绕过应用直改数据库种子名 | 制造“库内修改与种子 JSON 不一致”场景 |
| `23-run2-seed-log.txt` | 第二次启动日志 | `新增 0 条，跳过 6 条，失败 0 条`，重复启动幂等 |
| `24-run2-name-preserved.txt` | 二启后首次 API 读名称 | API 一度显示旧值，原因是绕过应用改库未失效缓存（cache-aside 已知行为，非种子覆盖） |
| `25-run2-session-reuse.txt` | 二启后复用旧 sessionId 再对话 | 会话在 Redis 中跨进程重启保留，历史消息条数=4，会话不依赖 JVM 内存 |
| `26-db-name-after-restart.txt` | 二启后直接查权威库 | 人工改名真实保留，证明种子初始化器绝不覆盖库内修改 |
| `27-api-after-cache-evict.txt` | 手工删除缓存键后再调 API | API 回源读到人工改名，闭环解释 24 与 26 的差异 |
| `28-final-stop.txt` | 验证结束停止应用 | 进程停止，8080 不再监听 |
| `29-final-db-state.txt` / `30-final-redis-state.txt` | 最终环境状态 | 表恢复为 6 个干净种子；Redis 无 `d15f:*` 残留 |
| `31-mvn-test-raw.txt` | 全量 `mvn test` 原始输出 | Tests run: 68, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS |
| `32-mvn-package-raw.txt` | `mvn package -DskipTests` 原始输出 | BUILD SUCCESS，fat jar 正常产出 |
| `33-app-run1-startup.log` / `34-app-run2-startup.log` | 两次启动的完整应用日志 | 含 HikariCP、MyBatis、Lettuce 连接初始化与种子初始化计数 |

## 复现步骤

```powershell
# 1. 建表（幂等，可重复执行）
Get-Content -Raw -Encoding UTF8 .\src\main\resources\db\schema-prompt-template.sql |
  & 'E:\SoftwareInstall\CyberSecurity\Mysql\mysql-commercial-9.7.0-winx64\bin\mysql.exe' `
  --host=127.0.0.1 --port=3306 -uroot -p123456 -D ai_db --default-character-set=utf8mb4

# 2. 全量测试（依赖本机 MySQL 与 Redis）
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' test

# 3. 打包并以 mock 模式启动
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' package -DskipTests
$env:AI_MODE='mock'
& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' -jar .\target\D15_FOPF_PLUS-1.0-SNAPSHOT.jar
```

## 未覆盖项的可信边界

- DeepSeek 真实厂商链路：本机未注入 `DEEPSEEK_API_KEY`，未做联网实证；`DeepSeekLiveSmokeTest` 保持 1 个跳过用例，真实调用不在本次证据范围。
- Redis 故障降级路径：代码实现了读异常回源 MySQL 的降级分支，但本次未制造 Redis 停机故障注入，该分支仅由代码评审与单元级逻辑保证，未留存真机故障证据。
- 会话持久化语义：会话按设计只存 Redis（滑动 TTL 30 分钟），TTL 到期或 Redis 数据丢失后会话不可恢复，这是明确的设计取舍而非缺陷；模板权威数据在 MySQL，不受 Redis 数据丢失影响。
