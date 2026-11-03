# BiQuote 个股与盘前盘后行情核查

核查时间：2026-11-03 00:18 UTC 左右（纽约 2026-11-02 19:18，盘后时段）。

## 结论与当前实现

BiQuote 确实有股票目录、报价与 OHLC 接口，但**本次没有核实到能满足美股正股盘前、盘后采样要求的可用数据**。因此不将财报个股采集主源从 Yahoo 切换到 BiQuote，也不将 `NVDAUSDT` 等标的自动映射为 `NVDA`。

这不是断言 BiQuote 永久不支持延长交易时段：文档未承诺明确的美股盘前/盘后数据口径，本次盘后实测也只有陈旧的同名股票报价。不能把“某次接口有价格”或“另一个 USDT 后缀标的有新报价”当作支持已确认。

## 官方接口与说明

- [API 文档](https://biquote.io/docs/)：`GET /api/symbols`、`GET /api/symbols/{name}`、`GET /api/{symbol}`、`GET /api/{symbol}/ohlc`。
- [MT5 数据源说明](https://biquote.io/guides/metatrader5-data-api.html)：明确列出 **Index & US equity CFDs**。这属于经纪商报价口径，不能据此视为交易所正股逐笔成交数据。
- 文档说明报价默认 `allowStale=true`：闭市时可用 HTTP 200 返回最后已知价格，附带 `marketState`、`stale`、`quoteAgeSeconds`、`lastQuoteAt`。
- `allowStale=false` 会在报价超过五分钟时返回 404，**不是只有未知股票才返回 404**。
- 本次文档中未找到明确的 `preMarket` / `postMarket` / `includePrePost` 或美股延长交易时段保证。没有这些字段本身不证明不支持，但需要进一步可靠证据才能接为盘前盘后主源。

## 盘后实测

公开接口直连查询，没有使用后端 Key、设备标识或其他秘密。

| 请求 | HTTP | 关键响应 |
|---|---|---|
| `/api/symbols/AAPL` | 200 | `name=AAPL`，`type=Stock`，`source=MetaTrader 5 (Broker 1)`，`hasData=false` |
| `/api/AAPL?allowStale=true` | 200 | `timestamp=2026-11-02T20:44:29Z`，`stale=true`，`marketState=closed`，`quoteAgeSeconds=12861` |
| `/api/AAPL?allowStale=false` | 404 | `No tick data available for 'AAPL'` |
| `/api/NVDA?allowStale=true` | 200 | `timestamp=2026-11-02T20:44:54Z`，`stale=true`，`marketState=closed`，`quoteAgeSeconds=12841` |
| `/api/NVDAUSDT?allowStale=true` | 200 | `timestamp=2026-11-03T00:18:56.648Z`，`source=aggr`，`stale=false`，`marketState=open`；但代码是 `NVDAUSDT`，不是 `NVDA` |
| `/api/symbols/ZZZZTESTNOTASTOCK123` | 404 | `Symbol 'ZZZZTESTNOTASTOCK123' not found` |

AAPL、NVDA 的一分钟 OHLC 请求也有返回，但最近的分钟仍停留在约 `2026-11-02T20:44:00Z`，不能用来填充本次盘后的实时采样。

`/api/symbols?type=Stock` 本次返回 1591 条目录记录；197 条 `hasData=true` 的记录均为 `USDT` 后缀标的，其余目录记录没有该标记。该标记是本次观测状态，不是永久覆盖承诺，也不能仅凭它决定股票不存在。

## 若后续证实支持，接入必须满足的条件

1. 通过准确代码查询目录，验证对应资产、报价币种和证券/衍生品口径；不猜测添加 `USDT` 后缀，不使用同名衍生品代替。
2. 能在盘前、盘后返回带有可靠来源时间的新报价；闭市旧价不能重标为当前价格。
3. 按用户要求，**仅在明确找不到对应标的时回退 Yahoo**。应以标的元数据查询的明确未找到结果判断，而不是把报价 404、`hasData=false`、陈旧价格、超时、限流或解析失败视为标的不存在。
4. 报价失败/陈旧/403/429 时保留缺口与错误，遵守 BiQuote 冷却，不偷偷改用 Yahoo 掩盖主源故障。
5. 若确实启用第二来源，持久化每个采样点的来源与价格口径，不能把 BiQuote 的经纪商 `mid` 与 Yahoo 正股成交/收盘价混为同一口径。

如果业务接受经纪商/USDT 衍生报价，或希望在“标的存在但没有新报价”时也回退 Yahoo，需要另行明确这些变化；当前不擅自放宽条件。
