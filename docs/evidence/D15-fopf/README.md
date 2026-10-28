# D15_FOPF 真机验证证据索引

- 验证时间：2026-10-28
- 运行环境：Windows + JDK 21.0.7 + Spring Boot 3.5.16 + Spring AI 1.1.8，`AI_MODE=mock`（离线模拟，零外网调用）
- 被测构件：`D15_FOPF-1.0-SNAPSHOT.jar`（`mvn package -DskipTests` 产物）
- 访问基址：`http://127.0.0.1:8080/api/v1/ai`
- 采集方式：`docs/temp_docs/D15-collect-evidence.ps1` 对真机 HTTP 接口实测落盘，未手工改写响应内容

## 证据清单

| 文件 | 内容 | 关键结论 |
| --- | --- | --- |
| `01-health-roles.txt` | GET `/health`、GET `/roles` | mock 模式自检通过；角色清单仅 customer_service、programming 两个启用角色 |
| `02-prompts-list-detail.txt` | GET `/prompts?status=ENABLED`、GET `/prompts/customer_service` | 6 个内置种子模板全部加载；详情含正文与变量声明 |
| `03-sync-role-chat.txt` | POST `/chat` 两轮同会话角色对话 + 无 scene 旧链路 | 首轮服务端生成 32 位 sessionId；第二轮 Mock 回显“历史消息条数=2”；旧链路响应不含会话字段 |
| `04-sse-role-stream.txt` | POST `/chat/stream` 两轮 SSE 原始帧 | start 帧携带 sessionId/scene/templateId/templateVersion；delta seq 从 0 连续；第二轮历史条数=2；响应头 text/event-stream、no-cache、X-Accel-Buffering=no |
| `05-template-admin.txt` | 模板创建/非法创建/启停/逻辑删除/删除后查询 | 含 ST4 转义大括号的合法模板创建成功；未声明占位符返回 4001；停用→启用→逻辑删除链路闭环；删除后查询 4001 |
| `06-error-paths.txt` | 非法 scene、未声明变量键 | 两类非法请求均返回业务码 4001 且带明确中文原因 |
| `07-static-pages.txt` | GET `/index.html`、GET `/templates.html` | 两个前端页面 HTTP 200，标题与脚本引用命中 |
| `08-mvn-test-raw.txt` | `mvn test` 完整原始输出 | Tests run: 63, Failures: 0, Errors: 0, Skipped: 1（跳过项为无 API Key 的 DeepSeek 联网冒烟），BUILD SUCCESS |
| `09-node-sse-parser-raw.txt` | `node --test` 原始 TAP 输出 | 9 用例全部通过，覆盖 LF/CRLF/CR、跨块、UTF-8 跨字节边界 |
| `10-app-startup-aop.txt` | 应用启动与 AOP 访问日志摘录 | 种子模板加载日志 1 条；CONTROLLER_ACCESS 切面事件 16 条，证明 D15 包名切点修复后切面真实生效 |

## 复现步骤

```powershell
# 1. 打包
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' package -DskipTests
# 2. mock 模式启动
$env:AI_MODE='mock'
& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' -jar .\target\D15_FOPF-1.0-SNAPSHOT.jar
# 3. 执行采集脚本（应用启动后另开终端）
& ..\..\..\..\docs\temp_docs\D15-collect-evidence.ps1
```

## 未覆盖项的可信边界

- DeepSeek 真实厂商链路：本机未注入 `DEEPSEEK_API_KEY`，未做联网实证；DeepSeek 实现的消息组装逻辑由代码评审与 Mock 链路保证，联网结果不在本证据范围。
- 持久化：模板与会话均为 JVM 内存实现，重启恢复种子数据；证据中的增删改仅证明单次运行期行为。
