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

## 启动

需要 Rust 1.85+（edition 2024）：

```bash
cd backend
cargo run
```

默认监听 `127.0.0.1:8080`，首次启动创建 `data/market.db` 并执行迁移。配置入口是
`config.toml`（可用 `APP_CONFIG` 指向别的文件），规则在 `rules.toml`。对外只需在 `[server]`
里填一对证书路径即可直出 HTTPS，见下文 [TLS](#tls后端直出不需要反向代理)。

最小可用环境变量：

```bash
export API_TOKENS="alice:$(openssl rand -hex 24)"     # 客户端令牌，name:token 逗号分隔
export OPENAI_API_KEY="sk-xxx"                         # 翻译与 AI 分析（可留空则自动关闭）
export TELEGRAM_BOT_TOKEN="123456:ABC..."              # 管理员告警
export TELEGRAM_ADMIN_CHAT_IDS="123456789"
cargo run
```

缺少 `API_TOKENS` 时 `[auth]` 会自动关闭（接口公开）并在日志与部署脚本中告警；
缺少 Telegram 配置时告警只写日志，服务照常运行。

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

- `[auth] enabled = true`，令牌来自 `API_TOKENS`（`name:token,name:token`），
  常量时间比较；`GET /health` 与 `GET /api/v1/meta` 免鉴权，其余全部要求
  `Authorization: Bearer <token>`。
- 令牌是配额与审计身份：`POST /events/{id}/ai-analysis` 按令牌每日计数
  （`[limits] ai_analysis_per_token_per_day`，默认 60）。超限时优先返回上一次分析结果
  （响应标 `rateLimited`），仅当该调用者没有任何缓存结果时才返回 `429` + `Retry-After`；
  进程内还有 `ai_concurrency` 个信号量限制同时打给模型的请求数。详见
  [限流](#限流超限时回退到上一次结果) 与 [审计](#模型调用审计按类别分类)。
- 错误统一为 `{"error":{"code","message"}}`，客户端按 `code` 本地化。

## API

免鉴权：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活检查 |
| GET | `/api/v1/meta` | `{version, apiVersion:1, capabilities, aiEnabled, serverTime}` |

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
| GET | `/api/v1/market/quotes?symbols=` | 批量当前行情与不可用列表 |
| GET | `/api/v1/events/{id}/ai-analysis?language&method&timezone` | 命中缓存 `200`，未生成 `404` |
| POST | `/api/v1/events/{id}/ai-analysis` | `{language, method, timezone, regenerate}`，限流时回退上次结果 |
| GET | `/api/v1/usage?days=7&recent=20` | 模型调用审计：按类别汇总的 token 消耗与最近记录 |
| POST | `/api/v1/translations/corrections` | 提交译名勘正（进入管理员审核） |
| GET | `/api/v1/translations/corrections?eventName=` | 最近一条勘正状态 |
| GET | `/api/v1/ws` | WebSocket，握手带同一个 Bearer |

事件对象沿用 camelCase 契约：`{id, provider, providerId, country, currency, category, event,
eventZhCn, eventZhTw, eventTime, importance, actual, previous, consensus, forecast, unit,
status, timeExact}`，宏观数值以字符串返回以避免浮点误差。`status` 取值包含
`scheduled | data_unavailable | released | collecting_market_data | analyzing | completed |
historical`。

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
- 服务端行情符号固定为客户端可展示的集合（`config.toml` 的 `[market] symbols`），
  客户端选择项只作为查询过滤。
- 已到公布时间但所有源都没有 Actual 的事件标记为 `data_unavailable`，**不使用当前价格伪造**。

## 用中转站（OpenAI 兼容）

后端从不绑定某一家模型厂商：只要对方提供 `POST {base_url}/chat/completions`，就能当上游。
翻译与 AI 简报是**两套独立配置**：可以不同中转站、不同模型、不同 Key。

```toml
[translation]
base_url = "https://relay-a.example.com/v1"
model = "deepseek-v4-flash"
api_key_env = "OPENAI_API_KEY"      # 便宜快的模型跑事件名翻译

[ai]
base_url = "https://api.openai.com/v1"
model = "gpt-5-mini"
api_key_env = "AI_API_KEY"          # 分析用另一个厂商/模型
```

对应两个环境变量（写在 `ENV_FILE`，权限 0640）：

```bash
OPENAI_API_KEY=sk-relay-a-...
AI_API_KEY=sk-openai-...
```

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
| `HTTP 401: invalid api key` | Key 与 base_url 不匹配（常见：拿 A 站的 key 填了 B 站地址），或环境变量没进 `ENV_FILE` |
| `HTTP 404: model not found` | 模型名要写中转站的写法（常带日期后缀或厂商前缀） |
| `HTTP 400 ... response_format` | 已自动降级：第一次被拒后后续批次不再发送 `response_format` |
| `HTTP 429` | 中转站限流；事件名翻译会自动重试，AI 简报会返回错误并让客户端稍后再点 |
| `relay returned a non chat-completions body` | 地址指向了网页而不是 API（缺 `/v1`，或该站不兼容 OpenAI 协议） |
| 一直停在英文事件名 | 看 `/status` 里的 `translation.relay` 与 `analysis.ai` 状态，它们会像数据源一样告警到 Telegram |

客户端直连模式也支持中转站：设置页填 endpoint、Key 与模型即可，模型名可从 `/models` 拉取（拉不到就手填）。

## AI 简报（服务端生成并缓存）

`POST /api/v1/events/{id}/ai-analysis` 由服务端调用 `[ai]` 配置的 OpenAI 兼容接口。
缓存键是 `(event_id, language, method, timezone)` —— **三种方法各自一行，永不互相覆盖**；
`regenerate=true` 只会替换同一种方法的那一行并把 `revision` 递增，另外两种方法原样保留。
客户端的 Room 缓存同样按 `(event, method)` 分键（迁移 v5），切方法立即可见各自的结果。

响应体（`AiAnalysis` 字段之上附加）：

| 字段 | 含义 |
|---|---|
| `fromCache` | 本次请求没有触发模型调用 |
| `rateLimited` | 请求被限流，返回的是上一次结果 |
| `retryAfterSeconds` | 距离可再次生成的秒数 |
| `usage` | `{promptTokens, completionTokens, totalTokens, calls}`（中转站未上报时缺省） |

## 限流：超限时回退到上一次结果

两层限制，都不会让用户拿到空白：

1. **同一 (事件， 方法) 的冷却窗口** —— `[limits] ai_regenerate_cooldown_seconds`（默认 600 秒）。
   窗口内点「重新分析」直接返回已缓存结果并标注 `rateLimited`。
2. **每令牌每日预算** —— `[limits] ai_analysis_per_token_per_day`（默认 60）。
   超限时先查缓存：有 → 返回上次结果 + `rateLimited` + `Retry-After`；
   完全没有 → 才返回 `429`（`error.code = quota_exceeded`）。

客户端会在本地应用同一冷却规则（后端模式下网络请求也一样被服务端拦住），并在分析页
明确提示「已达限流，显示上一次分析结果」。

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

人工勘正由管理员把关：

1. 客户端 `POST /api/v1/translations/corrections`；
2. 同名同内容 24 小时内重复直接复用；该词条已被屏蔽则返回 `{status:"muted"}` 且不通知；
3. 否则落库为 `pending` 并把带三个按钮的消息推给管理员：
   `✅ 采纳` / `❌ 拒绝` / `🔇 屏蔽该词条`（`callback_data` 为 `c:<id>` / `r:<id>` / `m:<id>`）；
4. 采纳 → 写入 `event_name_translation` 并回写所有同名事件，所有设备下次刷新即生效；
   屏蔽 → 写入 `translation_correction_mute`，该词条不再接受勘正、不再通知。

机器人命令：`/status`（数据源健康）、`/pending`（待审勘正）、`/help`。仅
`TELEGRAM_ADMIN_CHAT_IDS` 白名单内的 chat 会被响应。

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

告警键：`calendar.primary`、`calendar.fallback`、`calendar.all_sources`、
`calendar.data_missing`、`market.cnbc`、`market.yahoo`、`market.biquote`、`market.binance`。

触发规则：

1. 连续失败达到阈值 → `down`，通知一次；
2. 收到 429 / 限流 → 立即通知（受冷却约束）；
3. 主日历源降级到回退源 → `degraded`，通知一次；
4. 恢复 → 发一条恢复通知；
5. 公布时间已过 30 分钟仍无 Actual → 汇总成一条摘要（最多 10 条，单事件 6 小时内不重复）；
6. 主源与回退源同时不可用、或数据库写入失败 → critical，绕过静默时段。

静默时段只抑制非 critical 通知；所有发送尝试写入 `alert_event` 审计表，因此“机器人不出声”
与“数据源真的没问题”可以区分。Telegram 用 long polling（`offset` 落库），无需公网回调。

## 过去三个月：历史补采（可选）

补采是**管理员 CLI 命令**，需要具有历史日历权限的 Trading Economics 凭据，默认不执行：

```bash
sudo TE_API_KEY=xxx ./deploy.sh backfill
# 或指定 UTC 日期区间（最多 93 天）
cargo run -- --backfill 2026-06-08 2026-09-07
```

服务端自上线起持续采集并保留全部历史；补采只用于补齐上线之前的数据，重跑会跳过完整日期、
重试失败日期，事件与行情按唯一键幂等入库（见 `backend/tests/historical_backfill.rs`）。

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
sudo API_TOKENS="alice:$(openssl rand -hex 24)" \
     ./deploy.sh deploy --tls-cert /tmp/fullchain.pem --tls-key /tmp/privkey.pem
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
sudo API_TOKENS="alice:$(openssl rand -hex 24)" \
     TELEGRAM_BOT_TOKEN="123456:ABC..." \
     TELEGRAM_ADMIN_CHAT_IDS="123456789" \
     OPENAI_API_KEY="sk-xxx" \
     ./deploy.sh deploy --proxy http://127.0.0.1:7890 \
                        --tls-cert /tmp/fullchain.pem --tls-key /tmp/privkey.pem
```

`deploy.sh` 负责构建、安装、升级、备份、健康检查与卸载；密钥只写入
`/etc/market-analyzer/market-event-analyzer.env`（`0640 root:market`），不回显。
常用子命令：`status`、`logs`、`backfill [START END]`、`uninstall [--purge]`。

### Docker

```bash
cd backend
docker build -t market-event-analyzer .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e API_TOKENS="alice:xxx" -e OPENAI_API_KEY="sk-xxx" \
  -v ./data:/app/data market-event-analyzer
```

### GitHub Actions

`.github/workflows/backend-release.yml`：PR / 手动触发只验证 x86_64，推送 `v*` tag 时构建
完整矩阵并发布 Release。不想依赖 Actions 时本地跑 `./scripts/build-release.sh`（支持
`--musl`、`--target aarch64-unknown-linux-gnu`），产物在 `backend/dist/`。

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
