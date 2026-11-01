# 美股财报日历

“日历”页提供“宏观 / 财报”切换。直连与后端模式均支持，不新增底部导航、不改变宏观事件、关注、推送或 AI 分析的数据模型。

## 数据与边界

- **Nasdaq 主源**：`GET https://api.nasdaq.com/api/calendar/earnings?date=YYYY-MM-DD`。按日读取公司、股票代码、财季结束月份、美东盘前/盘后/未知、EPS 预期、分析师数量、市值、去年同期 EPS；部分历史记录另有已公布 EPS 和来源自带的超预期比例。
- **TradingView 补充与备用源**：`POST https://scanner.tradingview.com/america/scan`。Nasdaq 成功时按准确股票代码 + `is_primary` 查下一次日期、下一期 EPS / 营收预期；同名结果不唯一时不合并，不猜交易所或股份类别别名。Nasdaq 失败且无缓存时，按美东单日时间区间查询备用日历；时间一律未知、财季留空。
- 两者无需上游 API Key。它们是网站内部端点，没有公开稳定性或完整覆盖承诺，可能限流、变更、返回空字段；免鉴权不表示授权商用再分发。
- 以 **America/New_York 的日期**选日与分组，不能按设备时区把整条“盘后”事件移动到另一日。来源更新时间以设备时区显示。秒级时间戳只用于提取美东日期，不视为公司确认的精确发布时间。
- TradingView 的字段是 **下一期快照**，分开展示，不写入 Nasdaq 的季度预期字段。日期相同也不能证明财季、EPS 调整口径或财务币种一致；报价币种不是财报币种，营收不擅自添加美元符号。
- 不给过去日期或 Nasdaq 已有实际 EPS 的记录补“下一期预测”，避免把正常的下一财季当成日期冲突。未来未披露记录的日期不一致时展示冲突，不覆盖 Nasdaq 日期。
- 首版不提供公司确认状态、电话会时间、分钟级提醒、关注/改期通知、AI 分析、完整历史财务报表或秒级公布值监控。界面中所有日程均标注未获公司确认。

## 界面

支持前后日、今天、日期选择、股票代码/公司搜索以及盘前/盘后/未知筛选。默认今天指美东今天。Nasdaq 与 TradingView 分别保留更新时间；展示来源故障、较旧缓存、日期冲突和冷却提示，预测缺失显示破折号。

## 公司 Logo

财报卡片左侧按准确的美股代码加载公司 Logo。图片源为免鉴权的 FMP 静态 PNG：`https://images.financialmodelingprep.com/symbol/AAPL.png`，不调用其需要 API Key 的财务接口、不猜公司域名、不改变 Nasdaq / TradingView 财报字段。股份类别按原代码请求（如 `BRK.B`），没有图片时不猜别名。

直连模式使用数据源代理访问公开图片；后端模式仅访问自建后端的受保护图片转发接口，**不静默直连 CDN**。公开图片请求不携带后端 Key 或设备标识。后端通过网络源名 `earnings_logo` 选择代理，旧配置需要代理时自行将它加入 `[network].proxied`。

仅对可见卡片异步加载，不等待图片才显示财报。加载中、404、旧后端不支持、解码失败或网络异常时显示股票代码前两位占位；不会清空日历、触发财报告警或影响数值。切换数据源/后端地址会重建图片状态，丢弃旧来源的迟到结果。

内存缓存按模式和地址隔离，客户端最多 64 项、后端最多 128 项，成功缓存 24 小时、失败缓存 5 分钟；相同请求合并。直连还复用现有 OkHttp 磁盘缓存策略。403/429 按 Retry-After 冷却。仅允许固定图片主机和经过校验的股票代码，不接受任意图片 URL；单图不超过 256 KiB、PNG 尺寸不超过 1024×1024，界面缩小解码至约 256 像素以内。Logo 属于辅助识别信息，可能缺失、过期，商标权归原持有人。

无需新增数据库迁移或第三方图片加载依赖。

## 缓存、请求与故障

- 支持范围：美东今天之前 120 天至之后 60 天（含边界），不承诺上游在整个范围都有数据。
- 两端使用相同新鲜度策略：过去 7 天及今天 1 小时、未来 7 天 6 小时、其他日期 24 小时；手动刷新可绕过新鲜度，但每个日历缓存键的尝试至少间隔 60 秒。
- 每个上游请求间隔至少 1 秒，403/429 进入冷却，读取 `Retry-After` 秒数或 HTTP 日期，默认 300 秒，冷却范围 60 秒至 24 小时；不绕过反爬或关闭证书验证。
- Nasdaq 必须返回成功状态、正确日期和合法行结构，才能替换整个单日快照。明确的空数组/空 rows 是空日历；缺字段、错误状态、错日期或重复代码不是空日历。
- TradingView 单次最多请求 2000 行；若 `totalCount` 与返回行数不符、交易所代码重复，或备用日历返回其他日期，视为失败并提示，不把第一页或错误日期当完整结果。
- Nasdaq 失败时已有 Nasdaq 缓存优先保留，不用较弱备用数据覆盖；仅有 TradingView 备用缓存时，仍允许健康的 TradingView 更新它。两源失败且没有缓存时返回错误，不伪造空成功。失败不推进最后成功更新时间。
- Android 使用独立 Room 表，按直连/后端地址哈希隔离缓存；不存访问密钥或设备标识。模式/地址变更取消旧请求并丢弃迟到结果。后端不可达、未授权或旧版不支持时，不静默改用直连。
- Android 复用“数据源网络”的可选代理，只向自建后端发送后端鉴权和设备绑定头。后端按 `[network].proxied` 中的 `nasdaq`、`tradingview` 分别选路；旧配置的显式列表如未含 `nasdaq`，需由管理员自行加入才能代理它。

## 后端 API 与运行

```http
GET /api/v1/earnings?date=2026-11-02
Authorization: Bearer <existing backend key>

GET /api/v1/earnings?date=2026-11-02&refresh=true
```

公司图片接口为 `GET /api/v1/earnings/logo/{symbol}`，使用相同 Bearer / 设备绑定鉴权；成功返回 `image/png` 与 `X-Content-Type-Options: nosniff`，图片不可用返回 404，非法代码返回 400。该接口只接受股票代码，不接受来源 URL；不经过财报同步锁。

`date` 为 `YYYY-MM-DD`，省略时使用美东今天。查询受现有鉴权保护、响应 `Cache-Control: no-store`；上游免鉴权不改变后端访问密钥要求。`/api/v1/meta` 的 capabilities 新增 `earnings` / `earningsLogos`，API 协议版本仍为 1。

响应为单日对象（camelCase）：

```text
{ date, timezone, updatedAt, nasdaqUpdatedAt, tradingViewUpdatedAt,
  stale, warnings, events: [
    { symbol, name, source, session, fiscalQuarterEnding, epsForecast,
      analystCount, marketCap, actualEps, surprisePercent, lastYearEps,
      tradingView: { ticker, nextDate, nextEpsForecast, nextRevenueForecast,
                     quoteCurrency, dateConflict } | null }
  ] }
```

数值使用十进制字符串（分析师数量为整数），可空字段保持 null。`session` 为 `pre_market` / `after_hours` / `unknown`。警告码为 `nasdaq_unavailable`、`tradingview_unavailable`、`date_conflict`、`refresh_cooldown`；Android 本地刷新失败另用 `refresh_failed`。没有精确时刻或宏观事件 ID。

服务启动后独立后台任务每小时扫描今天至未来 60 天及过去 7 天，仅刷新到期日期，不阻塞 HTTP 启动。健康键为 `earnings_nasdaq` / `earnings_tradingview`，沿用现有来源健康与告警机制。

数据库升级：后端启动执行新增 `023_earnings_calendar.sql`；Android Room 9 → 10 只新增独立财报快照表，保留宏观事件、关注和分析。发布/部署仍遵循项目原有流程；本功能不自动部署或改动本机私人配置。

## 验证

- `cd backend && cargo test`：共享样例、十进制精度、空日历、来源故障、缓存保护、请求合并/冷却、已披露与历史记录隔离、API 鉴权与独立响应模型。
- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`：两源解析、夏令时 23/25 小时日界、故障与限流、模式/地址隔离、迟到结果丢弃、筛选与构建。
- 有测试设备时，运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.macroresearch.ui.earnings`：真实 Compose 搜索/筛选、预测口径提示、9 → 10 增量迁移。测试使用独立夹具，不切换设备上的真实数据源设置。

共享解析样例位于 `android/app/src/test/resources/earnings/`，Rust 测试读取同一份输入，以防两端语义漂移。自动化测试不依赖公开上游在线。
