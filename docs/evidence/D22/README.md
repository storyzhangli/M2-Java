# D22 证据索引（Evidence Index）

> 迭代：D22 模型配置外置与供应商无关服务接口
> 归档时间：2026-11-04
> 所有证据均为本机真实执行产物，未做任何手工改写；命令与结果可复现。

## 一、证据清单

| 证据文件 | 类型 | 对应验证项 | 结论 |
| --- | --- | --- | --- |
| `mock-startup.log` | 启动日志 | Mock 模式无 Key 启动 | 通过：`Started D22SpringAiApplication in 6.31 seconds`，Tomcat 8080，无异常 |
| `mock-sync-response.json` | 接口响应 | 同步接口 `POST /api/v1/ai/chat` | 通过：`code=200`、`mode=mock`、`model=offline-mock`、`mocked=true`、含 `sessionId` |
| `mock-health.json` | 接口响应 | 健康检查 `GET /api/v1/ai/health` | 通过：`code=200`，返回模式与上下文窗口 |
| `mock-sse-stream.txt` | 原始事件流 | 流式接口 `POST /api/v1/ai/chat/stream` | 通过：`start` + 16 个 `delta` + `complete`，SSE 命名事件完整 |
| `mock-access-log-lines.txt` | 访问日志 | 脱敏访问日志 / TraceId / 单行 JSON | 通过：`CONTROLLER_ACCESS/HTTP_REQUEST/HTTP_RESPONSE`，requestId 关联，无 Key 泄漏 |
| `deepseek-nokey-startup.log` | 失败日志 | 真实模式缺 Key 快速失败 | 通过：`AiConfigurationException ... AI_API_KEY ... 应用已拒绝启动`，不静默回退 |
| `full-test-2.log` | 全量测试日志 | 全量单元/契约测试 | 通过：`Tests run: 83, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |

## 二、验证口径与覆盖范围

- 编译：`mvn clean compile` 通过（`logs/D22/compile-main.log`，exit 0）。
- 全量测试：83 个测试全通过，其中基线 68 个、D22 新增 15 个（适配/校验/边界）。
- 真机：Mock 模式启动 + 同步/健康/SSE 三接口 + 访问日志全部实测通过。
- 负向：真实模式缺 Key 在 Bean 初始化阶段（`@PostConstruct`）即拒绝启动，无静默回退。

## 三、未验证项（诚实标注）

- 真实 DeepSeek 联网烟雾测试：本机无 `AI_API_KEY`，**未执行、未验证**；
  待注入真实 Key 后，按 `AI_MODE=deepseek` 启动并调用同步/流式接口补验。
- 证据中 `application` 字段仍显示历史常量 `D17_DHSA_MTDCA`，属健康接口既有展示值，
  与 D22 需求无关，本次未改动。
