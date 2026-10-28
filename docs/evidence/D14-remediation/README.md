# D14 验收整改证据清单（2026-10-28）

本目录存放 D14_SSE_RMT 验收整改后**可被 Git 跟踪的复核证据**。仓库 `.gitignore` 整体忽略了 `docs/temp_docs/` 且全局忽略 `*.log`，首版评审在 GitHub 上无法找到原始验证输出即源于此；因此工作过程文件仍按项目约定留在 `docs/temp_docs/`（不自动删除），最终精选证据复制到本目录，统一使用 `.txt` / `.md` 扩展名以避开忽略规则。已用 `git check-ignore` 逐文件确认本目录内容均不被忽略。

## 环境基线

| 项 | 值 |
|---|---|
| JDK | JDK 21（`D:\SoftwareInstall\JDK\JDK21`） |
| Maven | apache-maven-3.9.16（`D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16`） |
| 本地仓库 | `D:\SoftwareInstall\Apache\repository` |
| Spring Boot / Spring AI | 3.5.16 / 1.1.8 |
| Node（仅前端解析器测试） | v22.23.2，使用内置 `node:test`，零 npm 依赖 |
| 运行模式 | mock（离线，未发起 DeepSeek 联网调用） |

## 证据文件与复核方式

| 文件 | 内容 | 产生命令 / 方式 |
|---|---|---|
| `01-mvn-full-test-output.txt` | 全量 `mvn test` 原始输出，结尾 BUILD SUCCESS | `mvn test`（模块目录 D14_SSE_RMT） |
| `02-surefire-per-class-summary.txt` | 13 个测试类逐类结果，合计 37 用例、0 失败、0 错误、1 跳过 | 由 `target/surefire-reports/*.txt` 汇总 |
| `03-node-sse-parser-test-output.txt` | SSE 分帧器 9 个 Node 用例全部通过的原始输出 | `node --test src/test/node/sse-parser.test.js` |
| `04-sse-normal-frames.txt` | 真机正常链路原始 SSE 帧：响应头 + start + 25 个 delta + complete | 启动 jar 后 POST `/api/v1/ai/chat/stream` 抓包 |
| `05-sse-timeout-frame.txt` | 真机超时链路原始帧：start 后仅一个 error（code=5000，超时文案），无 complete | `AI_STREAM_TIMEOUT_MS=1` 启动后同样 POST 抓包 |
| `06-static-resource-verify.txt` | `/index.html` 200 且已引用 sse-parser.js、`/js/sse-parser.js` 200 | HTTP 实测 |
| `07-timeout-app-log-excerpt.txt` | 同一 requestId 的应用日志，含 reactor `parallel` 线程的应用层超时 WARN | 取自应用控制台输出 |

## 关键结论

1. Java 侧 37 个用例全绿（唯一跳过项 `DeepSeekLiveSmokeTest` 为无 API Key 时的设计性跳过，非失败），较首版 36 个新增 1 个超时专项用例。
2. Node 侧 9 个分帧用例全绿，覆盖 LF/CRLF/CR、逐字符切块、CRLF 跨 feed 切开、UTF-8 中文跨字节边界。
3. 真机证据同时覆盖正常收尾与超时收尾；超时帧由**应用层 Reactor timeout** 在连接健康时发出，不再依赖容器超时后能否写连接。
4. 本次未做 DeepSeek 真实联网实证（环境无 Key），该限制在整改文档中继续保留标注，不将 mock 结果表述为厂商链路验证。
