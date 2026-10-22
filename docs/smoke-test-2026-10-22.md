# 冒烟记录：真实百炼 + AgentForge 经网关调用（2026-10-22）

一次端到端冒烟，目的是确认两件事：网关能真正调用阿里云百炼，AgentForge 能经网关拿到回答。下面的请求头、SSE 和流水都是这次运行的原始输出，只删掉了密钥。

## 环境

| 部分 | 怎么跑的 | 真实 / 替身 |
|---|---|---|
| 百炼（DashScope OpenAI 兼容接口） | `https://dashscope.aliyuncs.com/compatible-mode/v1`，一把真实 key，网关首次启动时从环境变量导入并加密入库 | **真实** |
| model-gateway | `feat/caller-contract`，JDK 25，`spring-boot:run` | 真实进程 |
| 网关数据库 | H2 文件库（MySQL 模式），执行的是正式的 Flyway 脚本 V1、V2 | 替身：本机没有 MySQL |
| AgentForge | `feat/wecom-channel`，JDK 17，`agentforge.model.provider=gateway` | 真实进程 |
| AgentForge 的 MySQL / Redis / ES | MySQL、Redis 指向本机空端口，关闭 Flyway 和 hybrid 检索 | 都没连：聊天路径用不到。共享开发库和 ES 没有碰 |

网关给 AgentForge 签发的调用方 key：`team=platform, project=agentforge, routes=[rag-json, standard, light], allowOverseas=false`。

## 结果一览

| # | 调用 | 结果 |
|---|---|---|
| 1 | 直接调网关，路由 `standard` | 503 `NO_UPSTREAM_AVAILABLE`。qwen-plus 返回 403（账号免费额度用尽，并开启了"仅使用免费额度"），另外两个候选没有 key |
| 2 | 直接调网关，路由 `rag-json`，要求 JSON 输出 | **200**，qwen3.8-flash 服务，`Model-Check: MATCH`，返回合法 JSON |
| 3 | 直接调网关，带厂商私有字段 `enable_thinking` | 400 `UNSUPPORTED_FIELD`，没有调用上游，响应带请求号 |
| 4 | 直接调网关，路由 `light` | 503，qwen-turbo 返回 429（`RATE_LIMITED`），deepseek 没有 key |
| 5 | AgentForge `/api/v1/chat/stream`，聊天路由 `standard` | SSE `error` 事件：`MODEL_NO_UPSTREAM_AVAILABLE`、`retryable=true`、带网关请求号；用 correlation id `chat-smoke-1` 在网关流水里查到这次尝试 |
| 6 | AgentForge `/api/v1/chat/stream`，聊天路由临时指向 `rag-json` | **SSE 回答**，`model=dashscope/qwen3.8-flash`；流水 `SUCCESS`、`MATCH` |
| 7 | AgentForge，未登记的用户 | 401 |

第 6 步把聊天路由临时改成 `rag-json`，是因为这个账号上只有 qwen3.8-flash 还能用；`rag-json` 路由并不是给聊天用的。

## 原始证据

### 2. 网关直连，`rag-json` 路由，真实百炼回答

```text
HTTP/1.1 200
X-Gateway-Request-Id: req-1cbd1155-cb0f-4d9e-ac86-15515261d875
X-Correlation-Id: smoke-direct-2
X-Gateway-Route: rag-json
X-Gateway-Attempts: 1
X-Gateway-Trail: dashscope/qwen3.8-flash:OK
X-Gateway-Served-Provider: dashscope
X-Gateway-Served-Model: qwen3.8-flash
X-Gateway-Region: DOMESTIC
X-Gateway-Fallback: false
X-Gateway-Model-Check: MATCH

{"model":"qwen3.8-flash","content":"{\"rewrittenQuery\": \"季度申报需要准备哪些材料\"}",
 "usage":{"completion_tokens":12,"prompt_tokens":43,"total_tokens":55}}
```

请求体里没有 `enable_thinking`。按模型目录的 `extra-body` 配置，网关会给 qwen3.8-flash 注入 `enable_thinking=false`；这次没有抓取发往百炼的请求去核对这一点，单测 `UpstreamAdaptersTest` 覆盖了注入逻辑。

### 1. 403 时的降级与告警

```text
HTTP/1.1 503
X-Gateway-Request-Id: req-5806060e-da02-4ae4-befe-830e5725d7e1
X-Gateway-Trail: dashscope/qwen-plus:UPSTREAM_REJECTED;deepseek/deepseek-chat:NO_KEY;moonshot/moonshot-v1-32k:NO_KEY

告警（CRITICAL, UPSTREAM_REJECTED）：Upstream dashscope/qwen-plus refused our account (HTTP 403):
region, billing or entitlement — "Free quota exhausted. To continue accessing the model on a paid
basis, please add funds or disable the "use free tier only" mode in the management console."
```

流水：`failure_kind=UPSTREAM_REJECTED, http_status=403, cost=0`。按设计，403 不计入熔断，切到下一个候选，同时告警，告警里带上了厂商的原始说明。

### 5、6. AgentForge 经网关

失败（聊天路由 `standard`）：

```text
event:error
data:{"code":"MODEL_NO_UPSTREAM_AVAILABLE","retryable":true,
      "gatewayRequestId":"req-67622065-1544-426e-959b-1620cb7d05d8",
      "message":"model gateway returned HTTP 503 NO_UPSTREAM_AVAILABLE: ..."}

GET /admin/requests?correlationId=chat-smoke-1
[{"request_id":"req-67622065-1544-426e-959b-1620cb7d05d8","route":"standard","model":"qwen-plus",
  "outcome":"FAILED","failure_kind":"UPSTREAM_REJECTED","http_status":403,"cost":0, ...}]
```

成功（聊天路由 `rag-json`）：

```text
event:message
data:{"content":"小规模纳税人增值税通常按季度申报一次，但经税务机关核定也可按月申报。",
      "done":false,"provider":"gateway","model":"dashscope/qwen3.8-flash"}
event:done
data:{"content":"","done":true,"provider":"gateway","model":"dashscope/qwen3.8-flash"}

GET /admin/requests?correlationId=chat-smoke-2
[{"request_id":"req-83346900-3326-41a0-aaf8-6c45bbdb7895","route":"rag-json",
  "provider":"dashscope","model":"qwen3.8-flash","served_model":"qwen3.8-flash",
  "model_check":"MATCH","outcome":"SUCCESS","http_status":200,"cost":0.00002100,
  "currency":"CNY","latency_ms":1231}]
```

AgentForge 侧的 SSE 错误事件、网关响应头、网关流水，用同一个 correlation id 和请求号能对上。

### 用量归属

```text
GET /admin/usage?groupBy=project
[{"team":"platform","project":"agentforge","currency":"CNY","requests":5,"succeeded":2,"failed":3,
  "prompt_tokens":79,"output_tokens":29,"cost":0.00004110}]

GET /admin/usage?groupBy=model
qwen-plus 2 次失败 / qwen-turbo 1 次失败 / qwen3.8-flash 2 次成功；失败的都按 0 元记
```

调用 3 在网关校验阶段就被拒了，没有调用上游，所以不进流水。**金额是按模型目录里的占位单价算的**，不是百炼的实际价格。

## 这次发现的问题

- **不是所有错误都带 `X-Gateway-Request-Id`。** 请求体不是合法 JSON 时返回 400 `INVALID_JSON`，在进入网关处理流程之前就被拒了，不带请求号；key 无效的 401 也一样。README 原来写的是"每个响应（包括错误）都带"，已经改正。
- **测试脚本的坑：** Windows 的 Git Bash 下，用 `curl -d` 直接传中文，请求体会被转码成非法 UTF-8。要先写进文件，再用 `--data-binary @file` 发送。

## 没有验证的

- 真实 MySQL（网关和 AgentForge 都没接）；
- AgentForge 的知识检索路径（查询改写、可答性判定）经网关的调用：这条路径需要 ES，ES 不在本次范围内；
- qwen-plus 的成功路径：账号额度原因；
- 企业微信渠道：没有真实的企业。
