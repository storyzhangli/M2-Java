# D16 思考推理模式与引用来源标注 —— 真机/构建证据清单

> 采集日期：2026-10-29
> 模块：`D16/D16_CoT`（主包 `com.zl.d16`）
> 运行模式：`AI_MODE=mock`（默认离线模式，全程零外网请求；真实 DeepSeek 联调无 Key 未执行）
> 证据均为真实命令/HTTP 调用的原始产物，未手工改写；密钥仅使用 yml 占位串，证据中无真实密钥。

## 一、证据文件索引

| 编号 | 文件 | 类型 | 证明内容 |
| --- | --- | --- | --- |
| 01 | `01-app-startup.log` / `.err` | 应用日志 | jar 真机启动全量日志；`.err` 为 0 行，无 stderr 报错；含知识编排器、SSE、CONTROLLER_ACCESS 真实日志 |
| 02 | `02-sync-legacy.json` | HTTP 原始响应 | 不传任何 D16 新字段的旧请求：无 `thinkingEnabled`/`reasoningContent` 新字段，`citations` 为空列表，协议向后兼容 |
| 03 | `03-sync-thinking-show.json` | HTTP 原始响应 | `thinkingEnabled=true&showReasoning=true`：最终答案与 `reasoningContent` 分离，推理段含 `[MOCK-REASONING:D16]` 标记且不混入 content |
| 04 | `04-sync-thinking-hidden.json` | HTTP 原始响应 | 只开思考不展示：有 `thinkingEnabled:true`，响应中不出现 `reasoningContent` 字段 |
| 05 | `05-sync-effort-rejected.json` | HTTP 原始响应 | 显式传 `reasoningEffort=HIGH` 返回业务码 **4001**，文案说明 1.1.8 客户端不支持下发强度参数（拒绝而非静默吞参） |
| 06 | `06-sync-citation-hit.json` | HTTP 原始响应 | 设备故障问题检索命中 3 个真实片段：答案含服务端生成的 `[S1][S2][S3]`，结构化 citations 含 documentId/chunkId/section/page/excerpt，无 url 伪造字段 |
| 07 | `07-sync-citation-nohit.json` | HTTP 原始响应 | 无关问题（露营）零命中：`citations:[]`，答案无任何来源标记，不伪造引用 |
| 08 | `08-sync-citation-off.json` | HTTP 原始响应 | `retrieveEnabled=false`：即使问题命中语料也不检索、不返回来源 |
| 09 | `09-sse-thinking-citations.txt` | SSE 原始帧 | 全开关流式：`Content-Type: text/event-stream`，帧序 start → 15 个 thinking → 15 个 delta → citations → complete；citations 严格先于 complete |
| 10 | `10-sse-thinking-hidden.txt` | SSE 原始帧 | 思考但不展示：帧集只有 start/delta/complete，**无任何 thinking 事件** |
| 11 | `11-sse-legacy-frames.txt` | SSE 原始帧 | 旧请求流式：仅 start/delta/complete 旧帧集，无 thinking/citations 事件、start 帧无新字段 |
| 12 | `12-sse-citation-run1.txt` | SSE 原始帧 | 跨请求隔离第 1 次：编号从 S1 重新分配，citations 在 complete 前 |
| 13 | `13-sse-citation-run2.txt` | SSE 原始帧 | 跨请求隔离第 2 次（相同问题）：首个编号仍为 S1，证明注册表为请求级局部对象、无跨请求残留 |
| 14 | `14-static-index-head.txt` | HTTP 原始响应 | `GET /` 返回 200，页面标题为「D16 思考推理与引用来源演示」，新三开关与思考/来源区域已上线 |
| 15 | `15-app-stop.txt` | 进程记录 | 验证结束停止 PID 20092，停止后进程 gone，无残留 |
| 16 | `16-mvn-test-raw.txt` | 构建原始输出 | `mvn test` 全量：**Tests run: 106, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS** |
| 17 | `17-mvn-package-raw.txt` | 构建原始输出 | `mvn package -DskipTests` BUILD SUCCESS，产出 `D16_CoT-1.0-SNAPSHOT.jar`（约 55077.9 KB） |
| 18 | `18-node-test-raw.txt` | 前端解析器测试 | `node --test` TAP 输出：tests 12 / pass 12 / fail 0（含 D16 新增 3 个 thinking/citations 协议用例） |
| 19 | `19-deepseekchatmodel-javap.txt` | 依赖反编译 | `javap` 反汇编 Spring AI 1.1.8 DeepSeek 类（1154 行），实证无 thinking/reasoningEffort 字段、存在 reasoningContent 访问路径，是 4001 决策的硬证据 |

## 二、关键结论复核

1. **向后兼容**：02、11 号证据证明旧请求体/旧 SSE 帧集零变化（NON_NULL 隐藏新字段）。
2. **推理与答案严格分离**：03、04、09、10 号证据覆盖同步/SSE × 展示/隐藏四个组合。
3. **强度参数显式拒绝**：05 号证据为 4001 原始报文；拒绝依据有 19 号反编译证据支撑。
4. **引用只认真实检索**：06/07/08 覆盖命中、零命中、关闭三态；12/13 证明请求级编号隔离；提示注入夹具（语料 C03）由单测覆盖。
5. **测试真实性**：106 个 JUnit 用例 + 12 个 node 用例全部真实执行；唯一 skip 为无 Key 环境下的 `DeepSeekLiveSmokeTest`，属设计内跳过而非屏蔽。

## 三、未覆盖项（如实标注）

- 真实 DeepSeek API 联调：本机未配置 `DEEPSEEK_API_KEY`，未执行外网调用；真实链路的密钥校验、reasoner 切换与 reasoning_content 解析由 Mock/单测/反编译证据间接保证，待用户配 Key 后按 D16-02 文档第 6 章手工验证。
- 权限/租户隔离：当前项目无文档权限系统，该项不适用（任务书允许标注不适用）。
