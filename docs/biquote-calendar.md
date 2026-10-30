# BiQuote 财经日历备用源

Android 直连和 Rust 后端均按 **TradingView → BiQuote → Forex Factory** 请求。
前一源失败、数据格式无效或返回空时才尝试下一源；非空主源成功时不混入备用源。
失败不清空已有缓存，回退结果会携带降级提示。现有股票/外汇 BiQuote 行情接入不变。

## 接口与字段

核对日期：2026-10-28。官方说明：
- https://biquote.io/docs/#calendar
- https://biquote.io/guides/economic-calendar-api.html

请求 `GET https://biquote.io/api/calendar?from=<UTC ISO8601>&to=<UTC ISO8601>&limit=500`，
免 API Key；Android 有国家筛选时传 `countries=US,EU,...`。
返回裸 JSON 数组，按时间升序；默认仅 200 条，最大 500 条，无 offset 分页。

两端按不超过 24 小时的窗口分段；达到 500 条就二分该时间窗口重新请求。解析后按半开区间过滤，
用 occurrence ID 去重；仍无法排除截断时整次 BiQuote 请求失败并回退至 Forex Factory，不把部分结果标记为成功。
一次获取最多 512 个请求，防止错误响应造成无界重试。Android 使用设备时区的日界，
后端使用传入的 UTC 区间。取消 Android 请求会传播，不会继续请求备用源。

| BiQuote | 项目字段 / 处理 |
|---|---|
| `id` | `providerId`；包含 mql5/fxempire 前缀，保留原样 |
| `eventId` | 指标系列 ID，**不用作**单次事件 ID |
| `name / countryCode / currency / sector / time` | 名称、国家全名、货币、类别、UTC 时间 |
| `importance` | low / medium / high → 1 / 2 / 3 |
| `actual` | 公布值；null 不等于 0 |
| `forecast` | 同时写入 consensus / forecast；上游没有第二套独立预期 |
| `revisedPrevious / previous` | 优先使用修订前值，否则原前值 |
| `multiplier / unit` | 保留展示数值，不在解析时乘倍率；组合成 K jobs、M barrels、B USD、% 等 |
| `timeMode` | 仅 exact 为 `timeExact=true`；日期/待定时间保留但不进入精确时间公布轮询 |

扩散指数是唯一例外：真实响应中 `ISM Manufacturing PMI`、`ISM Non-Manufacturing PMI` 的 unit/multiplier 均为 `none`，两端按**精确全名闭集**补 `index points`（与 TradingView 的 ticker 闭集同理，不做通用标题猜测）；S&P Global PMI、地方联储指数等名称仍保持无单位。国家码映射补齐 BiQuote 独有代码：AE、BG、GE、HR、KZ、WW。

货币维度必须由显式 unit 确认：`unit=currency` 时才使用 currency 作为单位，
不能因为美国就业事件带 USD 就把人数误认成美元。未知单位原样保留，不按标题或数值大小猜倍率。
共享样本和边界测试见 `android/app/src/test/resources/calendar-units/biquote.json`。

## 配置与升级

后端 `[calendar]`：

```toml
biquote_url = "https://biquote.io/api/calendar"
tradingview_url = "https://economic-calendar.tradingview.com/events"
fallback_url = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
```

缺少新字段时自动使用 BiQuote 默认备用地址；TradingView 仍为主源。旧 `primary_url` 作为 `tradingview_url` 的兼容别名，
**不能同时填写这两个名字**。无需改动现有密钥配置。后端代理名单中加入 `biquote` 可代理
BiQuote 日历和行情；默认直连。Telegram `/test_calendar_api` 新增 BiQuote 备用源检测按钮。
健康键为 `calendar.primary`（TradingView）、`calendar.biquote`、`calendar.fallback`。

Android 日历使用设置中的日历代理，各源独立记录熔断和 Retry-After；升级后旧 TradingView
熔断时间不会误用于 BiQuote。Room v8 → v9 只新增时间精度列，不清空事件、关注或 AI 缓存。

切源时，仅在国家、精确时间和规范化标题都匹配且唯一时原位升级来源，保留旧事件 ID、
关注、译文和关联数据；歧义不合并。来源优先级为 TradingView > BiQuote > Forex Factory；优先级低的备用源不能覆盖已存在的公布值，但可以填补
缺失值。认证 Trading Economics 历史导入不参与公共源身份迁移。

## 数据边界

- 官方说明 BiQuote 为约前后 90 天的滚动窗口，不是全历史档案；TradingView 失败或为空才尝试 BiQuote，
  Forex Factory 仅覆盖本周。部分边界日缺数仍取决于上游覆盖，不能据此宣称历史完整。
- 非精确时刻、无公布值的讲话和假期仍保留为日历事件，不伪造数值。
- 仅新增一条有依据的跨源别名 `ISM Non-Manufacturing PMI` ↔ `ISM Services PMI`（ISM 报告改名，影响规则表早已把两者视为同一指标）；其余未被核对过的标题差异不合并，未匹配旧事件保留，需要在核对名称后单独处理。
- 只完成代码和本地验证；生产环境需更新后端并重启，客户端需重新构建安装。
