# model-gateway

统一大模型调用网关。所有业务方只走一个 OpenAI 兼容入口；网关负责把请求路由到境内外多家模型，处理失败切换，计量用量和成本，控制预算，托管厂商密钥，并核对实际调用的是不是付了钱的那个模型。

> 这是求职 demo，不是生产系统。下面的"已知限制"写明了哪些部分没有经过验证。

## 对照需求

| 需求 | 实现 |
|---|---|
| 各业务方走一个入口 | `POST /v1/chat/completions`，兼容 OpenAI 协议，现有 SDK 改 base URL 即可接入 |
| 后面接多家模型（通义 / DeepSeek / Kimi / Claude / GPT） | 一个 OpenAI 兼容适配器覆盖通义、DeepSeek、Kimi、GPT；Claude 走单独的 Messages API 适配器，负责协议互转 |
| 按任务复杂度路由 | 调用方在 `model` 字段里声明路由（`light` / `standard` / `reasoning`），每个路由对应一串有序的候选模型 |
| 用量与成本按团队、项目归属 | 每个调用方 key 绑定团队和项目；每次尝试写一行流水，提供 `GET /admin/usage?groupBy=team\|project\|model\|route` |
| 可限流、可告警 | 按 key 做 RPM 令牌桶；按团队设每日金额预算（先预占再结算）；用到 80% 和 100% 时告警，告警走日志或企微群机器人 |
| 密钥集中管理与轮换 | 厂商密钥用 AES-256-GCM 加密存储，密文带主密钥版本号；增加、停用密钥和轮换主密钥都不用重启 |
| 某一家不可用时自动降级 | 按失败类型决定是否切到下一个候选；熔断按"厂商 + 模型"统计；每个上游有并发上限 |
| 确认调用到的是付费的那个模型 | 上游地址只允许官方接入点（白名单）；逐次比对返回的 model 字段；按天和厂商账单对账 |
| 调用可追溯 | 每个响应（包括错误）都带 `X-Gateway-Request-Id`；调用方传入的 `X-Correlation-Id` 写进流水，能反查一次业务请求触发的全部模型调用 |

## 请求经过什么

```text
调用方 --Bearer gk-...--> 鉴权、RPM 限流
  -> 校验：只收标准字段；算出截止时间（取调用方声明和网关上限的较小值）
  -> 路由规划：数据是否允许出境、模型是否支持工具调用 / JSON 输出（只过滤，不改顺序）
  -> 逐个候选尝试：
       协议转换 -> 熔断检查 -> 并发上限 -> 取一把厂商密钥 -> 按上限预占预算
       -> 调用（不在这一层重试）-> 按失败类型决定下一步
       -> 写流水（同步写库）-> 按实际用量结算预算
  -> 响应头说明实际由谁服务：
       X-Gateway-Served-Provider / Served-Model / Region / Fallback / Attempts / Trail / Model-Check
```

## 调用方约定

**只收 OpenAI 标准字段。** 厂商私有参数（比如通义的 `enable_thinking`）直接返回 400 `UNSUPPORTED_FIELD`。需要这类参数的模型，在模型目录里配 `extra-body`，由网关在发往该模型时注入。原因是调用方不该知道这次请求会落到哪一家：降级到 DeepSeek 时还带着通义的私有参数，要么被拒，要么被静默忽略。

**`max_tokens` 一定发给上游。** 调用方没带时，网关按模型目录里的默认上限补上。这样预占预算用的上限和厂商实际执行的上限是同一个数。

**`X-Gateway-Deadline-Ms`：调用方还愿意等多久（毫秒）。** 网关取它和自身截止时间的较小值。剩余时间不够发起下一次尝试时，直接返回 504 `DEADLINE_EXCEEDED`，不会再调用上游。调用方已经放弃的请求，网关不再替它花钱。

**`X-Correlation-Id`：调用方自己的链路 ID。** 写进每一次尝试的流水，用 `GET /admin/requests?correlationId=` 反查。

**要求 JSON 输出的请求**（`response_format` 是 `json_object` 或 `json_schema`）会跳过不支持的模型。目前 Claude 适配器没做这项转换。

**错误码决定能不能重试，HTTP 状态码不行。** 同样是 429，限流可以稍后重试，预算用完当天就不会恢复；同样是 504，截止时间到了没调用上游，可以重试，而结果未知的那次可能已经扣了费。

| 错误码 | HTTP | 调用方能否自动重试 |
|---|---|---|
| `RATE_LIMITED` | 429 | 能，稍后 |
| `BUDGET_EXCEEDED` | 429 | 不能，当天预算不会恢复 |
| `NO_UPSTREAM_AVAILABLE` | 503 | 能 |
| `DEADLINE_EXCEEDED` | 504 | 能，这次没有调用上游 |
| `UPSTREAM_OUTCOME_UNKNOWN` | 504 | 不能，厂商可能已经扣费 |
| `UNSUPPORTED_FIELD`、`INVALID_*`、`UNKNOWN_ROUTE`、`NO_ELIGIBLE_UPSTREAM`、`UPSTREAM_BAD_REQUEST` | 400 | 不能，要改请求 |
| `ROUTE_NOT_ALLOWED` | 403 | 不能 |

## 关键取舍

**路由由调用方声明，不让 LLM 去判断任务复杂度。** 路由决策必须可审计、结果确定、不增加延迟。用一个模型去判断该用哪个模型，本身就会多花一次钱、多一次延迟，还可能判错。

**数据出境是硬规则，不是普通的路由偏好。** 每个上游都标了所在区域；调用方 key 默认不允许出境。没有放行的 key，境外模型既不会作为首选，也不会作为降级目标。设计评审时发现了一个坑：如果降级不看区域，境内模型一挂，财税数据就会被自动发到境外。

**失败类型决定下一步动作：**

| 类型 | 典型状态码 | 是否切下一个候选 | 是否计入熔断 | 怎么计费 |
|---|---|---|---|---|
| CONNECT_FAILURE | 连不上 | 切 | 计入 | 0 |
| RATE_LIMITED / SERVER_ERROR | 429 / 5xx / 529 | 切 | 计入 | 0 |
| UPSTREAM_REJECTED | 402 / 403 / 404 | 切，并告警 | 不计入 | 0 |
| UNAUTHORIZED | 401 | 在同一厂商里换下一把 key，坏 key 冷却 10 分钟 | 不计入 | 0 |
| BAD_REQUEST | 其他 4xx | 不切，直接返回给调用方 | 不计入 | 0 |
| READ_TIMEOUT / TRANSPORT_ERROR / MALFORMED_RESPONSE | 超时、断流、返回无法解析 | **不切，请求结束** | 计入 | **按上限估算入账** |

最后一行是故意的：读超时的时候，厂商很可能已经处理完并扣了费。这时换一家重试，可能为同一个回答付两次钱。所以这类情况记为 `UNKNOWN` 并按估算入账，留给每日对账去核实。

**预算按金额先预占、再结算。** 如果只在请求结束后累加用量，N 个并发请求可以一起超支，最多超出"N × 单次最大输出"。现在的做法是：调用前按"估算输入 + max_tokens"的上限预占额度，响应后换成实际花费。流水表是唯一事实来源，内存里的计数器在每天第一次使用时从流水重建，所以重启不会丢账，也不会重复计算。预算按币种分开设置，网关不做汇率换算，汇率是财务口径的事。

**计价按配置里的模型算，返回的 model 字段只用来比对。** 上游返回的 `model` 字段不能拿来计价：一是对方可以随便写，二是带日期的快照名（如 `xxx-2026-07-28`）是正常现象。比对规则只把"同名 + 日期后缀"和配置里的别名视为一致；`qwen-plus-mini` 这类同家族的其他型号会被标成 `MISMATCH` 并告警。

**模型真实性：最初的方案被砍掉了。** 起初的想法是用固定的探针 prompt 比对 `prompt_tokens`，同一模型对同一输入算出的 token 数是固定的。评审时指出两个问题：

- 同家族的大小模型往往共用分词器，被换成小模型时 token 数不变，而这恰恰是最常见的降级方式；
- token 用量是中转方自己报的，固定探针也很容易被识别出来，然后回放预期的数字。

所以最后换成三件事：

1. **控制链路**：上游地址只允许官方或云厂商托管的接入点，启动时就校验；出境流量只走 HTTP CONNECT 隧道，TLS 端到端加密。
2. **逐次比对**：比对返回的 model 字段。这是弱信号，主要用来尽早发现配置漂移。
3. **按天对账**：导入厂商后台的用量数据，和网关流水逐模型比对。如果厂商账单里出现一个网关从没调用过的模型，就说明 key 泄漏了，或者有流量绕过了网关，这是最强的信号。

**Anthropic 服务端的拒答兜底默认关闭。** Claude 的 API 支持请求被拒答时，由服务端自动换一个模型重跑。这对普通应用是好功能；但网关承诺的是"由配置的那个模型服务"，所以默认关闭。打开后，网关会通过 `usage.iterations` 识别出发生过兜底，并在 `Model-Check: UPSTREAM_FALLBACK` 里如实报告。

**熔断按"厂商 + 模型"统计，用时间窗口，4xx 不计入，慢调用不算失败。**

- 内部系统流量低、而且一阵一阵的。如果按最近 N 次调用统计，安静的路由可能几个小时都保持"不健康"。
- LLM 回答慢是正常现象；真正卡死的情况由整体截止时间兜住。

**虚拟线程配合并发上限。** 同步写法加虚拟线程，代码比 WebFlux 简单；网关大部分时间在等上游，正好适合虚拟线程。代价是虚拟线程本身没有线程池那种天然的背压，所以每个上游各有一个信号量，控制同时在途的请求数。

**流水同步写库。** 500 人规模下，每次写库多几毫秒可以接受。如果改成异步队列，进程一崩就会丢账。默认不保存 prompt 和回答正文，只存请求体的哈希，因为财税数据敏感。

**为什么不直接用现成的开源网关？** 路由、限流、计费这些通用能力，成熟的开源网关已经有了。真要落地，我会先评估它们能不能覆盖上面这几个和合规强相关的决策点：

- 失败分类与重复计费
- 数据出境规则
- 计费口径
- 对账

覆盖不了再决定是二次开发还是自研。这个项目是把这些机制自己实现一遍，弄明白哪里该改、哪里不能信。

## 运行

依赖：JDK 25、Maven、MySQL 8。

```bash
export GATEWAY_MASTER_KEYS="v1:$(openssl rand -base64 32)"
export GATEWAY_ADMIN_TOKEN="change-me"
export GATEWAY_DB_URL="jdbc:mysql://localhost:3306/model_gateway?serverTimezone=UTC"
export DASHSCOPE_API_KEY="sk-..."   # 可选：没有密钥的厂商，首次启动时从这里导入
mvn spring-boot:run
```

签发一个调用方 key（明文只返回这一次）：

```bash
curl -X POST localhost:8090/admin/client-keys -H "X-Admin-Token: change-me" -H "Content-Type: application/json" \
  -d '{"team":"finance","project":"vat-filing","routes":["standard","reasoning"],"allowOverseas":false,"rpm":60}'
```

AgentForge 接入时用的是 `rag-json` 路由（查询改写和可答性判定，都要求按 JSON Schema 输出）。这个路由只放用这套 prompt 评测过的模型；降级到没测过的模型，结果会悄悄变掉。知识库片段不能出境，所以这把 key 必须 `allowOverseas=false`：

```bash
curl -X POST localhost:8090/admin/client-keys -H "X-Admin-Token: $GATEWAY_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"team":"platform","project":"agentforge","routes":["rag-json","standard"],"allowOverseas":false,"rpm":120}'
```

调用：

```bash
curl localhost:8090/v1/chat/completions -H "Authorization: Bearer gk-..." -H "Content-Type: application/json" \
  -d '{"model":"standard","messages":[{"role":"user","content":"小规模纳税人季度申报要准备什么？"}]}' -i
```

管理接口（请求头需带 `X-Admin-Token`）：

- 厂商密钥：`POST /admin/provider-keys`、`POST /admin/provider-keys/{id}/disable`、`POST /admin/provider-keys/reseal`（主密钥轮换的第二步，用新版本重新加密全部密钥）
- 调用方 key：`DELETE /admin/client-keys/{id}`
- 查询：`GET /admin/usage`、`GET /admin/requests/{requestId}`（单个请求的每次尝试）、`GET /admin/budgets/{team}`、`GET /admin/upstreams`（熔断与在途数）、`GET /admin/alerts`
- 对账：`POST /admin/reconciliation`

模型目录在 `application.yml` 里。除 Claude 外，**其余价格都是占位数字，上线前要按厂商官网价目表更新**。

## 测试

```bash
JAVA_HOME=<jdk-25> mvn test
```

共 57 个测试。上游都是 JDK `HttpServer` 写的假服务，可以按需返回指定的状态码、延迟和用量；数据库用 H2 的 MySQL 模式，执行的是生产环境同一套 Flyway 脚本。端到端测试 `GatewayFlowTest` 走真实 HTTP，覆盖以下场景：

- 503 后切换到下一家，两次尝试都写了流水，费用按缓存和非缓存分开计算
- 读超时不重试，按估算入账
- 401 后换同一厂商的另一把 key
- 不允许出境的 key，境外上游一次都没被调用
- Claude 协议互转
- 400 不切换
- 预算不足时，在调用任何上游之前就拒绝
- 返回的 model 不一致时标记并告警
- 连续失败后熔断打开，不再请求该上游
- RPM 限流
- 管理接口签发 key，响应和数据库里都看不到厂商密钥明文
- 对账识别出"厂商账单里有、网关从没调用过"的模型
- 厂商私有字段被拒，错误响应同样带请求 ID
- 调用方的截止时间不够时，一次上游都不调用，直接返回 504
- `X-Correlation-Id` 原样返回，写进流水，能用管理接口反查

`DemoCatalogTest` 用仓库里自带的 `application.yml` 启动，确认发布出去的模型目录能正常绑定（`extra-body`、JSON 输出能力、`rag-json` 路由）。

## 已知限制（没做或没验证的）

- **没有接过真实的 MySQL 和真实厂商。** 只在 H2 上跑过 Flyway 脚本；本机没有 MySQL。也没有调用过任何真实的厂商 API。
- **跨境代理没有实测。** 凭经验需要注意两点：JDK HttpClient 只支持 HTTP CONNECT 代理，不支持 SOCKS；HTTPS 隧道上的 Basic 代理认证默认是关闭的（`jdk.http.auth.tunneling.disabledSchemes`）。
- **不支持流式输出。** 请求里带 `stream=true` 会直接返回 400。
- **Claude 路由只支持纯文本对话。** 工具调用、图片、tool 消息、JSON 输出都没做协议转换；遇到这类请求会跳过 Claude，换下一个候选。
- **`extra-body` 只支持 OpenAI 兼容协议的上游**，值只能是字符串、布尔值或数字。
- **预算、限流、熔断的状态都在单个 JVM 里。** 只适用于单实例部署；多实例需要把计数器换成 Redis 等共享存储，接口不用变。
- **管理接口只有一个静态 token。** 没有接 SSO，也没有细分权限。
- **主密钥来自环境变量。** 生产环境应该放在 KMS 里。
