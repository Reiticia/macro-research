# 美股财报日历

“日历”页使用统一月视图，支持“全部 / 财报 / 经济事件”筛选，点击日期查看当日日程。直连与后端模式均支持，不新增底部导航、不改变宏观事件、关注、推送或 AI 分析的数据模型。

## 数据与边界

- **Nasdaq 主源**：`GET https://api.nasdaq.com/api/calendar/earnings?date=YYYY-MM-DD`。按日读取公司、股票代码、财季结束月份、美东盘前/盘后/未知、EPS 预期、分析师数量、市值、去年同期 EPS；部分历史记录另有已公布 EPS 和来源自带的超预期比例。
- **TradingView 补充与备用源**：`POST https://scanner.tradingview.com/america/scan`。Nasdaq 成功时按准确股票代码 + `is_primary` 查下一次日期、下一期 EPS / 营收预期；同名结果不唯一时不合并，不猜交易所或股份类别别名。Nasdaq 失败且无缓存时，按美东单日时间区间查询备用日历；时间一律未知、财季留空。
- 两者无需上游 API Key。它们是网站内部端点，没有公开稳定性或完整覆盖承诺，可能限流、变更、返回空字段；免鉴权不表示授权商用再分发。
- 以 **America/New_York 的日期**选日与分组，不能按设备时区把整条“盘后”事件移动到另一日。来源更新时间以设备时区显示。秒级时间戳只用于提取美东日期，不视为公司确认的精确发布时间。
- TradingView 的字段是 **下一期快照**，分开展示，不写入 Nasdaq 的季度预期字段。日期相同也不能证明财季、EPS 调整口径或财务币种一致；报价币种不是财报币种，营收不擅自添加美元符号。
- 不给过去日期或 Nasdaq 已有实际 EPS 的记录补“下一期预测”，避免把正常的下一财季当成日期冲突。未来未披露记录的日期不一致时展示冲突，不覆盖 Nasdaq 日期。
- 首版不提供公司确认状态、电话会时间、分钟级提醒、关注/改期通知、AI 分析、完整历史财务报表或秒级公布值监控。界面中所有日程均标注未获公司确认。

## 界面

- 周日起始的六周月视图，支持前后月、今天和日期选择；跨月日期可点击。日期格显示最多四个财报预览位置（公司 Logo 或溢出数量），彩色圆点与数量表示经济事件；小屏和大字体时月格区域可滚动，按可见周加载 Logo。
- 点击日期打开当日日程底部面板，跟随“全部 / 财报 / 经济事件”筛选；支持事件/公司/股票搜索、经济事件重要性多选、财报盘前/盘后/未知筛选。经济事件卡片仍可进入原有详情页，财报卡片保留 EPS、预测、来源与冲突信息。
- 默认今天指美东今天。财报始终按美东日期分格，经济事件按设备时区分格并显示实际时间；页面和日列表都明确标注两种时区口径，不给财报捏造精确发布时间。
- 月视图先读本地财报快照，按单日接口逐步更新，优先当前选中日期，不并发轰炸上游；切月、切数据源或隐藏某类数据会取消对应旧请求。经济事件用单次可见月范围查询（42 天），不逐日重复请求；国家和重要性筛选同时作用于月格与日列表。
- 加载中显示省略点，失败/旧缓存日期显示警示，超出财报支持范围显示范围提示；未加载或请求失败不当作成功空日。认证失败、旧后端不支持或连续故障会停止余下月请求，避免请求风暴。Nasdaq 与 TradingView 分别保留更新时间；展示来源故障、较旧缓存、日期冲突和冷却提示，预测缺失显示破折号。

## 公司 Logo

财报卡片左侧按准确的美股代码加载公司 Logo。图片源为免鉴权的 FMP 静态 PNG：`https://images.financialmodelingprep.com/symbol/AAPL.png`，不调用其需要 API Key 的财务接口、不猜公司域名、不改变 Nasdaq / TradingView 财报字段。股份类别按原代码请求（如 `BRK.B`），没有图片时不猜别名。

直连模式使用数据源代理访问公开图片；后端模式仅访问自建后端的受保护图片转发接口，**不静默直连 CDN**。公开图片请求不携带后端 Key 或设备标识。后端通过网络源名 `earnings_logo` 选择代理，旧配置需要代理时自行将它加入 `[network].proxied`。

仅对可见卡片异步加载，不等待图片才显示财报。加载中、404、旧后端不支持、解码失败或网络异常时显示股票代码前两位占位；不会清空日历、触发财报告警或影响数值。切换数据源/后端地址会重建图片状态，丢弃旧来源的迟到结果。

客户端内存缓存按模式和地址隔离，最多 64 项。后端不使用 Logo 内存缓存或 SQLite：图片下载后保存为独立 PNG 文件（如 `data/earnings-logos/logo-AAPL.png`），同名 `.json` 保存过期时间、是否有图片和 LRU 访问时间；最多 128 项，超限同时删除最久未访问项的 PNG 与元数据，每次命中从文件读取，重启后继续复用。两端成功缓存 24 小时、失败缓存 5 分钟，刷新失败保留旧图；相同请求合并。直连还复用现有 OkHttp 磁盘缓存策略。403/429 按 Retry-After 冷却，后端冷却截止时间写入目录内的 `cooldown.json`，重启不会清除冷却。仅允许固定图片主机和经过校验的股票代码，不接受任意图片 URL；单图不超过 256 KiB、PNG 尺寸不超过 1024×1024，界面缩小解码至约 256 像素以内。Logo 属于辅助识别信息，可能缺失、过期，商标权归原持有人。

后端通过可选配置指定图片目录，旧配置未填写时使用默认值：

```toml
[earnings_logo]
cache_dir = "data/earnings-logos"
```

相对路径以 `config.toml` 所在目录为基准，也可指定绝对路径；目录自动创建，需确保服务进程有读写权限。PNG 和元数据先写同目录临时文件，再原子替换，避免中断下载覆盖旧图；读取时校验尺寸与大小，损坏/丢失文件按缓存未命中处理。目录由单个服务进程独占，不应由多实例同时写入。该目录是可删除重建的缓存，不随 SQLite 数据库备份；需要保留时单独备份目录。无需新增数据库迁移或第三方图片加载依赖。修改配置模板后，自动部署仍按原有基线规则处理，旧私人配置不自动改写。

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

数据库升级：后端启动执行 `023_earnings_calendar.sql`（Logo 文件缓存不新增数据库迁移）；Android Room 9 → 10 只新增独立财报快照表，保留宏观事件、关注和分析。发布/部署仍遵循项目原有流程；本功能不自动部署或改动本机私人配置。

## Telegram 财报 API 诊断

管理员私聊发送 `/test_earnings_api [YYYY-MM-DD] [股票代码]`，默认纽约今天与 `AAPL`，通过按钮分别测试 Nasdaq 财报、TradingView 财报和公司 Logo。也可以从原 `/test_calendar_api` 菜单进入默认财报测试；新命令已加入机器人命令菜单与 `/help`。

- 日期支持纽约今天前 120 天至后 60 天，股票代码只接受经过校验的代码，不接受 URL 或路径。TradingView 仅测试今天及未来的下一期快照，历史日期不会发起请求。
- 使用运行中的同一个 `EarningsService` / `CompanyLogoService`，因此代理路由、请求串行化及 403/429 冷却与生产请求一致，不额外创建能绕过冷却的诊断客户端。Nasdaq/TradingView 同源同日期测试至少间隔 60 秒；Logo 测试全局至少间隔 60 秒，间隔记录在缓存目录 `probe.json`，重启仍保留。
- 每次独立请求所选上游，不使用日历缓存、磁盘旧 Logo 或其他源的成功结果掩盖失败；不写财报快照、不覆盖缓存 PNG，也不重置后台健康计数。仅更新必要的限流/诊断间隔状态。
- 回复显示纽约请求日期、直连/代理、HTTP 状态、响应格式、`rCode`、`data`/`rows` 类型和数量、规范化 `asOf`、`totalCount` 等白名单证据。Logo 显示 HTTP、MIME、PNG 校验、字节数与尺寸。JSON 上限 2 MiB，PNG 上限仍为 256 KiB；单次上游请求最长 10 秒。
- 有效空数组或合法 `rows:null` 可以显示 `validated_rows=0`；`data:null`、字段缺失、错日期、HTML 和截断仍判失败，不推断为空日。日期数据缺失也不等于全源断网，需结合请求日期和上游覆盖范围判断。
- 后台健康错误同样附带请求日期与响应结构。连续失败次数按实际日期请求累计，而不是按小时；本次未改变告警阈值或把结构异常降格为成功。
- 测试只对白名单管理员的本人私聊开放，先确认 Telegram 回调再等待上游，结果编辑原消息；不回显原始响应正文、上游任意消息、请求 URL、代理凭据或服务器真实配置。

本功能仅需更新后端，不需要更新 APP；未改动配置模板或新增数据库迁移。

## 验证

- `cd backend && cargo test alert::bot::earnings_tests --lib`：机器人入口/菜单、管理员与回调校验、分源请求、缓存与健康隔离、HTTP/结构错误证据、限流复用、响应体大小限制及 Logo 实时校验；测试使用本地模拟服务，不依赖公开上游。
- `cd backend && cargo test`：共享样例、十进制精度、空日历、来源故障、缓存保护、请求合并/冷却、已披露与历史记录隔离、API 鉴权与独立响应模型。`cargo test --test earnings_logos` 还验证指定目录自动创建与独立 PNG 保存、服务重建后复用图片/失败缓存/冷却、过期刷新与旧图兜底、文件损坏/缺失恢复、非法路径拦截及 128 项 LRU 文件淘汰。
- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`：两源解析、夏令时 23/25 小时日界、故障与限流、模式/地址隔离、迟到结果丢弃、筛选与构建；`MonthCalendarTest` 覆盖跨月/跨年/闰年网格、时区分格、可见类型请求隔离、选中日期优先、旧月份结果隔离与认证失败限流。
- `MonthCalendarUiTest`（包 `com.macroresearch.ui.calendar`）验证月格类型筛选、日期与月份选择、经济事件详情跳转及财报错误/时段筛选；需连接设备后运行仪器测试，构建成功不等同于已在设备执行。
- 有测试设备时，运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.macroresearch.ui.earnings`：真实 Compose 搜索/筛选、预测口径提示、9 → 10 增量迁移。测试使用独立夹具，不切换设备上的真实数据源设置。

共享解析样例位于 `android/app/src/test/resources/earnings/`，Rust 测试读取同一份输入，以防两端语义漂移。自动化测试不依赖公开上游在线。
