# Macro Research 后端

自建的统一数据服务：上游抓取、缓存、规则与 AI 分析、事件名翻译、WebSocket 推送，
以及数据源异常时的 Telegram 管理员告警。Android 客户端可以**直连公开数据源**，也可以
**切到后端模式**只依赖这一个域名。

```text
Android (direct | backend)
        │  HTTPS + Bearer
┌───────┴─────────────────────────────────────────────────────────────┐
│ Axum REST + WS          auth / quota / meta / status                │
│ CalendarService         TradingView → Forex Factory 回退            │
│ MarketService           CNBC(美债) → Yahoo / BiQuote / Binance      │
│ AnalysisService         规则引擎 (rules.toml)                        │
│ AiAnalysisService       服务端生成并缓存 AI 简报（3 种方法）         │
│ TranslationService      事件名翻译 + 勘正审核                        │
│ AlertService → Telegram 数据源告警 + 勘正按钮 + /status              │
│ SQLite                  事件 / 观察 / 行情 / 分析 / 翻译 / 告警 / 配额│
└─────────┬───────────────────────────────────────────────────────────┘
          │ [network].proxy_url（仅被墙源与 Telegram）
     出网代理 / 直连
```

## 启动：一个配置文件，零必需环境变量

需要 Rust 1.85+（edition 2024）：

```bash
cd backend
cp config.example.toml config.toml   # 真实配置不入库，密钥写在这份里
cargo run
```

配置全部在 **一个 TOML 文件** 里，包括令牌、中转站密钥与 Telegram 凭据。`config.toml` 已进
`.gitignore`（仓库只跟踪模板 `config.example.toml`）。查找顺序：

1. `APP_CONFIG` 环境变量指向的文件（唯一可选的环境变量，通常不用设）；
2. 可执行文件同目录的 `config.toml`（systemd 部署即 `/opt/market-analyzer/config.toml`）；
3. 当前工作目录的 `config.toml`（`cargo run` 的开发场景）。

找不到时不会静默使用默认值，而是报 `没有读到配置文件 …，请先 cp config.example.toml config.toml`。
首次启动创建 `data/market.db` 并执行迁移；日志首行打印实际加载的配置路径。对外只需在
`[server]` 里填一对证书路径即可直出 HTTPS，见下文 [TLS](#tls后端直出不需要反向代理)。

部署相关的键（其余默认值见 `config.toml` 注释）：

```toml
[server]
host = "0.0.0.0"
port = 443
log_level = ""                       # 留空用 info；RUST_LOG 环境变量仍然优先

[auth]
enabled = true
tokens = "pixel:9f2c…,emulator:5a1d…"   # 客户端令牌，一台设备一个名字，便于审计区分

[translation]
api_key = "sk-relay-…"               # 中转站密钥直接写在这里

[ai]
api_key = "sk-relay-…"

[telegram]
bot_token = "123456:ABC…"
admin_chat_ids = "123456789"

[backfill]
te_api_key = ""                      # 仅 --backfill 需要
```

规则：

- **凭据只从配置文件读**，不再有 `API_TOKENS` / `OPENAI_API_KEY` 这类环境变量；只剩
  `APP_CONFIG`（路径）与 `RUST_LOG`（日志）两个普通变量，都不是凭据；
- 缺某个密钥只关闭对应的子系统并打印缺哪个键，服务本身照常启动：
  日历、行情、历史仍可用，只是没有翻译/AI/告警/鉴权；
- 文件里是明文密钥：仓库只放模板，`config.toml` 被忽略；服务器上部署脚本会把它设为
  `0640 root:market`。

缺少令牌/密钥时的行为：`[auth]` 自动关闭并在日志与部署脚本中告警；Telegram 未配置时告警
只写日志；翻译与 AI 未配置密钥则拒绝启动该子系统（服务本身仍能起来）。

## 出网代理

国内主机访问不了 TradingView、CNBC、Yahoo 与 Telegram，因此有一个共享的出网代理开关：

```toml
[network]
proxy_url = "http://127.0.0.1:7890"    # 或 socks5://127.0.0.1:1080
proxied = ["tradingview", "forexfactory", "cnbc", "yahoo", "telegram"]
request_timeout_seconds = 20
```

- 留空即全部直连；`proxied` 里没列出的源（BiQuote、Binance、模型接口）走直连，
  这样代理故障不会连带拖垮局域网内可达的源。
- 每个源的失败独立计数并独立告警，见下文。
- 部署脚本可用 `--proxy http://127.0.0.1:7890` 写入该配置。

## 鉴权与配额

- `[auth] enabled = true`，令牌来自配置文件的 `auth.tokens`（`name:token,name:token`），
  常量时间比较；`GET /health` 与 `GET /api/v1/meta` 免鉴权，其余全部要求
  `Authorization: Bearer <token>`。
- 令牌是配额与审计身份：共享 AI 分析首次请求时才生成，并计入该令牌的每日预算；缓存命中
  不消耗配额。客户端自带 Key 的分析直接请求模型，不经过服务端。进程内的
  `ai_concurrency` 信号量仍限制同时打给模型的请求数。
- 错误统一为 `{"error":{"code","message"}}`，客户端按 `code` 本地化。
- 错误统一为 `{"error":{"code","message"}}`，客户端按 `code` 本地化。

### 令牌怎么发放、吊销

令牌不在线上申请，由管理员自己生成并写进配置文件（或环境变量）；App 里填的就是这个字符串。

1. 生成一段随机值（不要用可猜的词）：

   ```bash
   openssl rand -hex 24        # Linux / Git Bash / WSL
   ```

   ```powershell
   # Windows PowerShell
   -join ((1..48) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
   ```

2. 以 `名字:令牌` 写进服务器上的配置文件（一台设备一个名字，审计时才能分清是谁用的）：

   ```bash
   sudo nano /opt/market-analyzer/config.toml   # [auth] tokens = "pixel:…,emulator:…"
   sudo systemctl restart market-event-analyzer
   ```

   升级时 deploy.sh 会保留已填的密钥，不会被仓库里的模板覆盖。

3. 把令牌填进 App（设置 → 数据来源 → 后端 → 访问令牌）。

- **吊销**：从 `auth.tokens` 里删掉对应条目并重启服务即可，旧 App 立即收到 401。
- **审计**：`/api/v1/usage` 与 Telegram `/usage` 按令牌名字区分消耗，所以建议按设备命名。
- **本机调试**不想发令牌：`config.toml` 里 `[auth] enabled = false`，接口完全公开，
  仅限回环或内网使用。
- 注意：`auth.enabled = true` 而 `auth.tokens` 为空时，服务会告警并自动关闭鉴权（不会
  默默公开接口而无人知晓）；部署脚本也会明确提示。

## API

免鉴权：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活检查 |
| GET | `/api/v1/meta` | `{version, apiVersion:1, capabilities, aiEnabled, serverTime}` |
| POST | `/api/v1/translations/names` | 批量读取已缓存的事件名译名（最多 100 个；只读、不触发模型） |

需要 Bearer：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/status` | 各数据源健康状态 + 本令牌今日配额用量 |
| GET | `/api/v1/events/upcoming?days=7` | 近期事件 |
| GET | `/api/v1/calendar?from&to&country&minimum_importance` | 单日区间 ≤90 天 |
| GET | `/api/v1/events/history?country=a,b&category&limit&offset&from&to` | 历史分页，`country` 支持逗号分隔多值 |
| GET | `/api/v1/events/{id}` | `{event, observations}` |
| POST | `/api/v1/events/{id}/refresh` | 绕过同步间隔按事件当日重取公布值 |
| GET | `/api/v1/events/{id}/analysis` | 规则报告 |
| GET | `/api/v1/events/{id}/market` | `{snapshots, reactions}` |
| GET | `/api/v1/events/by-provider?provider&provider_id` | 按上游身份查事件（直连客户端映射共享结果用） |
| GET | `/api/v1/market/quotes?symbols=` | 批量当前行情与不可用列表 |
| GET | `/api/v1/events/{id}/ai-analysis?language&method` | 共享分析，缓存未命中时惰性生成当前语言和方法 |
| GET | `/api/v1/ai-analysis/by-provider?provider&provider_id&language&method` | 按上游身份读取或惰性生成共享分析 |
| POST | `/api/v1/events/{id}/analysis-feedback` | `{language, method, revision, message}`；同一结果只记一条 |
| GET | `/api/v1/usage?days=7&recent=20` | 模型调用审计：按类别汇总的 token 消耗与最近记录 |
| POST | `/api/v1/translations/corrections` | 提交译名勘正（进入管理员审核） |
| GET | `/api/v1/translations/corrections?eventName=` | 最近一条勘正状态 |
| GET | `/api/v1/ws` | WebSocket，握手带同一个 Bearer |

事件对象沿用 camelCase 契约：`{id, provider, providerId, country, currency, category, event,
eventZhCn, eventZhTw, eventTime, importance, actual, previous, consensus, forecast, unit,
status, timeExact}`，宏观数值以字符串返回以避免浮点误差。`status` 取值包含
`scheduled | data_unavailable | released | collecting_market_data | analyzing | completed |
historical`。

启动时服务会把「已有公布值、已超出监视窗口、但状态仍停留在
`scheduled / timeout / data_unavailable`」的事件批量改为 `historical`：这些事件不可能再
进入监视（例如停机期间公布、之后由日历同步补到数值），客户端本就把它们显示为历史事件。
窗口内的 `scheduled` 事件不受影响，仍由监视器接管。

WebSocket 消息：

```json
{"type":"economic_event_released","eventId":7,"event":"CPI YoY",
 "eventZhCn":"消费者价格指数同比","eventZhTw":"消費者價格指數同比",
 "actual":"3.2","consensus":"3.1"}
```

## 数据源与降级

| 子系统 | 主源 | 回退 |
|---|---|---|
| 财经日历 | TradingView `economic-calendar.tradingview.com/events`（免 Key） | Forex Factory 本周 JSON |
| 美债收益率 | CNBC 报价 + 1 分钟 K 线 | Yahoo `^UST2YR` / `^TNX` |
| 指数 / 贵金属 / 美元 | BiQuote | Yahoo |
| 加密 | Binance (`data-api.binance.vision`) | — |

- 回退周历与主源对同一指标使用不同标题，因此维护一份**封闭且逐条核对过的同义表**；
  合并时把主源的公布值补写到**恰好唯一匹配**（国家 + 精确时刻 + 规范化标题）的回退行上，
  有歧义就不写。主源的行永远不会被回退源覆盖，也不会删除任何行。
- CNBC 请求固定携带 `Accept: application/json`、网页 `Referer` 与应用 User-Agent，避免被
  Akamai 将无来源的接口请求判为爬虫而返回 403。
- 服务端行情符号固定为客户端可展示的集合（`config.toml` 的 `[market] symbols`）。每个标的的
  当前价格与涨跌幅保存在进程内缓存中，默认每 5 秒主动刷新一次；各标的的首次刷新均匀错开，
  `/api/v1/market/quotes` 只读缓存，不会因客户端同时轮询而放大上游请求。刷新失败时最多保留
  `live_quote_stale_seconds` 秒并标记 `stale`。
- 已到公布时间但所有源都没有 Actual 的事件标记为 `data_unavailable`，**不使用当前价格伪造**。

## 用中转站（OpenAI 兼容）

后端从不绑定某一家模型厂商：只要对方提供 `POST {base_url}/chat/completions`，就能当上游。
翻译与 AI 简报是**两套独立配置**：可以不同中转站、不同模型、不同 Key。

```toml
[translation]
base_url = "https://relay-a.example.com/v1"
model = "deepseek-flash"
api_key = "sk-relay-a-..."          # 中转站密钥就写在配置文件里

[ai]
base_url = "https://api.openai.com/v1"
model = "gpt-5-mini"
api_key = "sk-openai-..."           # 分析用另一个厂商/模型
```

凭据只从配置文件读（已没有 `OPENAI_API_KEY` 之类的环境变量）。缺哪个键，启动日志会直接指出，
并且只关闭对应的子系统，不影响其余功能。

### base_url 怎么写

三种写法都行，不必手改代码：

| 你手上的地址 | 实际请求 |
|---|---|
| `https://relay.example.com/v1` | `https://relay.example.com/v1/chat/completions` |
| `https://relay.example.com/v1/chat/completions` | 原样使用 |
| `https://relay.example.com` | `https://relay.example.com/chat/completions` |

必须以 `http://` 或 `https://` 开头；非 https 仅适合局域网自建网关。

### 中转站的额外要求

`extra_headers` 覆盖需要额外头或非 Bearer 鉴权的网关：

```toml
[ai]
# OpenRouter 类
# extra_headers = { "HTTP-Referer" = "https://github.com/you/macro-research", "X-Title" = "macro-research" }

[translation]
# Azure 类：用 api-key 而不是 Bearer。若写 Authorization，它会直接替掉默认的 Bearer 头。
# extra_headers = { "api-key" = "azure-key" }
```

### 中转站自身被墙

把 `translation` / `ai` 加进出网代理名单即可（默认直连）：

```toml
[network]
proxy_url = "http://127.0.0.1:7890"
proxied = ["tradingview", "cnbc", "yahoo", "telegram", "translation", "ai"]
```

### 配完先自检

```bash
sudo ./deploy.sh check-ai          # 已部署的服务器上
cargo run -- --check-ai            # 开发机上，读当前目录 config.toml
```

它不碰数据库、不写任何分析结果，只发一次最小请求并打印**中转站自己的回复或错误**：

```text
== 中转站自检 ==
出网代理: http://127.0.0.1:7890

[翻译] base_url=https://relay-a.example.com/v1 model=deepseek-v4-flash
  ✅ Nonfarm Payrolls → 非农就业 / 非農就業

[AI 分析] base_url=https://relay-a.example.com/v1 model=deepseek-v4-flash
  ✅ 回复: ok
```

### 常见报错对照

| 现象 | 原因 / 处理 |
|---|---|
| `HTTP 401: invalid api key` | Key 与 base_url 不匹配（常见：拿 A 站的 key 填了 B 站地址），或 `[translation]/[ai] api_key` 忘了填 |
| `HTTP 404: model not found` | 模型名要写中转站的写法（常带日期后缀或厂商前缀） |
| `HTTP 400 ... response_format` | 已自动降级：第一次被拒后后续批次不再发送 `response_format` |
| `HTTP 429` | 中转站限流；事件名翻译会自动重试，AI 简报会返回错误并让客户端稍后再点 |
| `relay returned a non chat-completions body` | 地址指向了网页而不是 API（缺 `/v1`，或该站不兼容 OpenAI 协议） |
| 一直停在英文事件名 | 看 `/status` 里的 `translation.relay` 与 `analysis.ai` 状态，它们会像数据源一样告警到 Telegram |

客户端直连模式也支持中转站：设置页填 endpoint、Key 与模型即可，模型名可从 `/models` 拉取（拉不到就手填）。

## 共享 AI 分析：服务端生成，结果冻结

服务端采用惰性生成：用户首次请求某个已公布事件的某个语言和方法时，才调用 `[ai]` 配置的
OpenAI 兼容接口，并且只生成当前请求的这一条。缓存键是
`(event_id, language, method, timezone)`，语言固定 en / zh-CN / zh-TW；之后相同请求直接读取
数据库，不再调用模型。不存在规则分析报告时，首次请求会先生成确定性的规则报告；未公布的事件
不会触发 AI 调用。并发请求同一个缓存键时，服务端只保留一次模型调用。

- 后端模式使用 `GET /api/v1/events/{id}/ai-analysis`；按 provider 查询使用
  `GET /api/v1/ai-analysis/by-provider`，两者都需要 Bearer token。缓存命中直接返回，缓存未命中
  才同步生成当前语言/方法；
- 读者对结果有异议时 `POST /api/v1/events/{id}/analysis-feedback` 提交反馈，同一结果
  （同一 revision）只记一条；
- 反馈通过 Telegram 推送给管理员，消息带「重新分析 / 忽略」按钮；只有管理员点击
  「重新分析」才会把 `target_revision + 1` 的任务入队并递增该行的 `revision`，
  忽略则标记后不再提醒；
- 任务逐条重试并记录失败原因；某条失败不影响其他事件与其他方法。

响应体（`AiAnalysis` 字段之上附加）：

| 字段 | 含义 |
|---|---|
| `fromCache` | 首次生成返回 `false`，之后命中数据库返回 `true` |
| `rateLimited` | 普通首次请求为 `false`（保留字段，兼容客户端解析） |
| `usage` | 生成时的模型消耗（中转站未上报时缺省） |

客户端自带 Key 时走另一条路：设备直接请求用户配置的模型接口，payload 与结果都不经过
服务端，本地 Room 缓存按 `(event, method)` 分键（迁移 v5），切方法立即可见各自的结果。

## 管理员才能重新生成

首次生成按 Bearer token 计入 `[limits] ai_analysis_per_token_per_day`，缓存命中不消耗配额。
`ai_concurrency` 限制同时打给模型的任务数。用户反馈后仍由管理员决定是否重新生成，
`ai_regenerate_cooldown_seconds`（默认 600 秒）约束管理员批准后的重生成频率。

## 模型调用审计（按类别分类）

每次模型调用都写入 `llm_usage` 审计表，记录：**类别**（`scope` = `ai_analysis` / `translation`
；分析内部再分 `kind` = `ex_ante` / `comparison` / `single_pass`）、调用方令牌、事件、语言与
方法、模型名、中转站主机、四项 token 计数（含 `reasoning_tokens` 与 `cached_tokens`）、
耗时、重试次数与成败原因。

查看：

```bash
curl -H "Authorization: Bearer $TOKEN" \
     "https://relay.example.com/api/v1/usage?days=7&recent=20"
```

或直接在 Telegram 里发 `/usage`（近 24 小时按类别汇总 + 最近 3 条）。

记录保留 `[limits] llm_usage_retention_days` 天（默认 180，设 0 关闭审计），
在提供汇总前自动清理，无需额外定时任务。

方法与客户端一致：

1. `method=1` 只用公布值与规则信号，行情永不进入 payload；
2. `method=2`（默认）两段式：先产出事前链条与展望，再把该事前结果与真实行情一起发回，
   要求逐条标注 `confirmed` / `contradicted` / `unobserved`；
3. `method=3` 一次给出全部内容。

### 思考等级与厂商专属参数

推理模型（gpt-5 / o 系列、DeepSeek reasoner、Qwen thinking 等）需要两样东西，都已在配置里：

```toml
[ai]
max_tokens = 8192                    # 思考 token 也计入输出预算，需调大
# 推理模型普遍不认 max_tokens：中转站报错时服务端会自动换 max_completion_tokens 重试一次，
# 也可以直接写死：
# max_tokens_param = "max_completion_tokens"

# 思考等级 / 思考预算等厂商专属参数，原样并入请求体（messages 受保护，不可覆盖）：
extra_body = { reasoning_effort = "low" }                                  # OpenAI
# extra_body = { enable_thinking = false }                                 # Qwen
# extra_body = { thinking = { type = "enabled", budget_tokens = 2048 } }    # Anthropic 风格
```

同一字段写在 `[translation]` 下即可控制翻译批次的思考与采样行为（翻译通常不需要）。

没有可用分钟行情时三种方法都退化为“只给数字与规则”。模型回包解析（markdown 围栏、
reasoning 混排、schema 回显、截断、`snake_case`、`verdict` 归一化）与设备端共用同一份语料：
`android/app/src/test/resources/ai-analysis/`，由 Kotlin 与 Rust 两侧测试共同校验，避免两套
实现漂移。

## 翻译与勘正审核

事件名翻译沿用 `event_name_translation` 缓存 + 启动补翻，返回的事件带 `eventZhCn/eventZhTw`。
客户端不需要个人 Key 就能拿到译名：后端模式下译名随事件返回；直连模式配置了后端时，
客户端会通过免鉴权的 `POST /api/v1/translations/names` 批量拉取已缓存的译名（最多 100 个/批，
两种模式都会请求，并覆盖列表内全部可见事件），同样不消耗服务端或客户端的模型额度。
该请求失败时客户端会在设置页显示原因，不会静默显示英文——未配置后端或后端不可达时
事件名保持英文原文。

人工勘正由管理员把关：

1. 客户端 `POST /api/v1/translations/corrections`；
2. 同名同内容 24 小时内重复直接复用；该词条已被屏蔽则返回 `{status:"muted"}` 且不通知；
3. 否则落库为 `pending` 并把带三个按钮的消息推给管理员：
   `✅ 采纳` / `❌ 拒绝` / `🔇 屏蔽该词条`（`callback_data` 为 `c:<id>` / `r:<id>` / `m:<id>`）；
4. 采纳 → 写入 `event_name_translation` 并回写所有同名事件，所有设备下次刷新即生效；
   屏蔽 → 写入 `translation_correction_mute`，该词条不再接受勘正、不再通知。

机器人命令：`/status`（数据源健康）、`/pending`（待审译名勘正）、`/usage`（近 24 小时模型用量）。
仅配置文件里 `telegram.admin_chat_ids` 白名单中的 chat 会被响应。

## 告警

```toml
[telegram]
enabled = true
api_base = "https://api.telegram.org"
poll_timeout_seconds = 30

[alerts]
failure_threshold = 3            # 连续失败次数
cooldown_seconds = 3600          # 同一问题重复提醒间隔
data_missing_after_minutes = 30  # 已到公布时间仍无 Actual
data_missing_cooldown_seconds = 21600
digest_max_events = 10
quiet_hours = ""                 # 例 "23:00-07:00"，默认关闭
quiet_hours_timezone = "Asia/Shanghai"
```

告警键：`service.startup`、`calendar.primary`、`calendar.fallback`、`calendar.all_sources`、
`calendar.data_missing`、`market.cnbc`、`market.yahoo`、`market.biquote`、`market.binance`、
`analysis.ai`、`translation.relay`。

触发规则：

1. 连续失败达到阈值 → `down`，通知一次；
2. 收到 429 / 限流 → 立即通知（受冷却约束）；
3. 主日历源降级到回退源 → `degraded`，通知一次；
4. 恢复 → 发一条恢复通知；
5. 公布时间已过 30 分钟仍无 Actual → 汇总成一条摘要（最多 10 条，单事件 6 小时内不重复）；
6. 主源与回退源同时不可用、或数据库写入失败 → critical，绕过静默时段。

静默时段只抑制非 critical 通知；所有发送尝试写入 `alert_event` 审计表，因此“机器人不出声”
与“数据源真的没问题”可以区分。Telegram 用 long polling（`offset` 落库），无需公网回调。

**启动通知**：服务每次启动都会给管理员发一条，包含监听地址与协议（http/https）、数据库路径、
鉴权/翻译/AI 的启用状态与所用模型、出网代理，以及最近一次模型调用时间。它**不受静默时段
抑制**（夜间重启也值得关注，且第一次启动必须证明 bot 配置可用）；崩溃循环（systemd 自动拉起）
时会每个重启各发一条，这也是预期行为——那是真出了问题。

## 启动时刷新最近 7 天

每次正常启动都会在后台按天刷新最近 7 个 UTC 日期（含今天），更新事件数值、观测记录与缺失译名。已有译名使用共享缓存，不重复调用模型，也不覆盖人工勘正；翻译需启用并配置 `[translation]`，否则仍保存原文事件。

该任务不阻塞 HTTP 服务启动，与原有未来日历同步独立运行；单日抓取失败会记录日志并继续下一天。主源失败时仍使用周历回退，但周历不保证覆盖过去 7 天，降级会记录警告。没有抓到的日期不会清空已有数据。启动刷新不是过去三个月的完整历史补采。

## 过去三个月：历史补采（可选）

补采是**管理员 CLI 命令**，需要具有历史日历权限的 Trading Economics 凭据，默认不执行：

```bash
sudo ./deploy.sh backfill            # 需先在配置文件里填 [backfill] te_api_key
# 或指定 UTC 日期区间（最多 93 天）
cargo run -- --backfill 2026-06-08 2026-09-07
```

服务端自上线起持续采集并保留全部历史；补采只用于补齐上线之前的数据，重跑会跳过完整日期、
重试失败日期，事件与行情按唯一键幂等入库（见 `backend/tests/historical_backfill.rs`）。

本地市场数据修复（`--repair-market`）事件取本地库，不需要 TE key，也不会插入重复事件：

```bash
cargo run -- --repair-market 2026-09-16 2026-09-18   # 单日可只写一个日期
```

用于修复「已公布但没有任何市场证据」的本地事件：停机错过监视窗口、或实况采集窗口内行情全部
失败。处理条件：`timeExact`、+60 分钟窗口已结束；已有实况反应的报告与实况流水线中的事件
一律跳过。空壳报告与历史证据会被重建，失败日期重跑时自动重试缺失窗口（见
`backend/tests/market_repair.rs`）。修复后报告带 HistoricalEvidence，事件状态落 `historical`；
所用 K 线同时写入该事件的 market_snapshot 分钟级样本，客户端反应时间线与详情页价格列
据此展示，与实况事件一致。

### 数据真实性与覆盖范围

- 日历按天调用官方 API，验证返回事件全部落在请求的 UTC 日期区间，达到 1000 条响应上限时
  拒绝可能被截断的数据。保留全天/模糊时间事件，但 `timeExact=false` 的事件不计算日内反应。
- `Forecast` 映射市场共识，`TEForecast` 映射数据源自身预测；历史数值可能已修订。
- 每天每个配置资产只获取一次行情窗口（含前 10 分钟、次日 61 分钟），同一发布组复用缓存。
- 免费 Yahoo 保守使用最近 7 天的 1 分钟线；更老的窗口标记 `retention_limit`，
  **不以小时线/日线伪装分钟反应**。
- 美债收益率变化单位是基点，其他资产为百分比；不存在基准、休市、样本不足一律保留缺失，
  不插值、不用当前报价。

## TLS：后端直出，不需要反向代理

客户端强制 HTTPS（Bearer 令牌 + `wss://`），但**不要求**你装 Caddy/Nginx：后端可以自己终结
TLS，只需两份 PEM。

```toml
[server]
host = "0.0.0.0"                      # 直出 TLS 时必须对外可连
port = 443
tls_cert = "/etc/market-analyzer/tls/fullchain.pem"
tls_key  = "/etc/market-analyzer/tls/privkey.pem"
```

两项同时填写即启用，任一为空（或都为空）则退回明文 HTTP。部署脚本直接接受证书：

```bash
sudo ./deploy.sh deploy --tls-cert /tmp/fullchain.pem --tls-key /tmp/privkey.pem
```

它会以 `0640 root:market` 安装到 `/etc/market-analyzer/tls/`，把 `--host` 默认改为 `0.0.0.0`，
并把本机健康检查切到 `https://127.0.0.1/health`（自签名校验用 `-k`）。

### 证书从哪来

| 方式 | 适合 | 续期 |
|---|---|---|
| **Cloudflare Origin Certificate** | 域名托管在 Cloudflare，或服务器在国内无备案 | 最长 15 年，基本不用管 |
| certbot（Let's Encrypt） | 域名已解析到本机、80 端口可用 | `certbot renew --deploy-hook 'systemctl restart market-event-analyzer'` |
| 反向代理（Caddy） | 想自动续期、或机器上已经跑着代理 | 自动 |
| 隧道（cloudflared） | 不想开 443、不想备案、不要域名 | 自动，地址是 cf 给你的 https 域名 |

后端不会热加载证书：续期后重启一次服务即可。

### 还是想用明文 HTTP？

只在两种情况下合理：内网机器之间，或流量已经被隧道加密。客户端为此保留了一个**显式开关**
（设置页“允许明文 HTTP（仅限内网或隧道地址）”），但**只对内网 / 回环 / CGNAT（Tailscale）
地址生效**，公网域名与公网 IP 一律拒绝，避免令牌被明文发送到互联网。

反向代理仍然是可选方案（已有 Nginx、想自动续期时）：

```caddyfile
macro.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 300s;
}
```

两种拓扑下客户端填的都是 `https://<域名>`，设备端在「设置 → 数据来源 → 后端」填地址与管理员
发放的令牌，点「测试连接」调 `/api/v1/meta` 校验地址、证书与协议版本。

## 部署

### Linux 服务器（systemd，推荐）

```bash
cd backend/deploy
sudo ./deploy.sh deploy --proxy http://127.0.0.1:7890 \
                        --tls-cert /tmp/fullchain.pem --tls-key /tmp/privkey.pem
# 然后填密钥并重启：
sudo nano /opt/market-analyzer/config.toml   # auth.tokens / translation.api_key / ai.api_key / telegram.*
sudo systemctl restart market-event-analyzer
sudo ./deploy.sh check-ai                    # 验证中转站连通
```

`deploy.sh` 负责构建、安装、升级、备份、健康检查与卸载；它把仓库里的 `config.toml`（或
模板 `config.example.toml`）装到 `/opt/market-analyzer/config.toml`（`0640 root:market`），
**升级时保留已填的密钥**。凭据一律写在配置文件里，不再使用环境变量。常用子命令：
`status`（含各密钥是否已配置，值不回显）、`logs`、`check-ai`、`backfill [START END]`、
`uninstall [--purge]`。

### Docker

```bash
cd backend
docker build -t market-event-analyzer .
# 二进制默认读同目录的 config.toml（镜像里是 /app/config.toml），挂载覆盖即可：
docker run --rm -p 127.0.0.1:8080:8080 \
  -v $PWD/config.toml:/app/config.toml \
  -v ./data:/app/data market-event-analyzer
# 也可以用 APP_CONFIG 指到挂载进来的其他路径：
#   -e APP_CONFIG=/run/secrets/config.toml
```

### GitHub Actions

`.github/workflows/backend-release.yml`：PR 与手动触发都会构建验证 x86_64；推送分支或 Tag
不再自动运行。要发布时在 Actions 对 `v*` tag 手动运行工作流，构建并创建 Release。不想依赖
Actions 时本地跑 `./scripts/build-release.sh`（支持 `--musl`、`--target aarch64-unknown-linux-gnu`），
产物在 `backend/dist/`。

## 验证

```bash
cd backend
cargo fmt -- --check
cargo test
```

覆盖：TradingView / Forex Factory / CNBC / Yahoo / BiQuote / Binance 解析与降级、
同义表合并（含歧义不写、主源不被覆盖）、鉴权 401/200、每日配额 429、
告警状态机（阈值 / 冷却 / 恢复）、翻译勘正（去重、屏蔽、采纳回写）、
共享语料的 AI 回包解析，以及既有的仓库、历史分页、状态机与补采测试。
所有合成数据只写内存数据库，不混入真实数据。
