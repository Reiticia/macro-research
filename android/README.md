# Macro Research Android

宏观财经事件研究客户端。默认直接访问公开数据源，也可以只依赖一台自建后端
（见 [后端说明](../docs/backend.md)）。

## 数据来源模式

设置页顶部的「数据来源」卡片可以在两种模式间切换，**默认直连**：

- **直连**：设备直接请求 TradingView / CNBC / Yahoo / BiQuote / Binance，可选 HTTP 代理；
  AI 分析与翻译使用你自备的 OpenAI 兼容 Key。
- **后端**：只请求你的服务器（地址 + 访问令牌），数据、译名与 AI 简报均由服务端提供，
  无需个人 Key，并可通过 WebSocket 接收事件公布提醒。

后端可以是 `https://你的域名`（后端自己出证书，或用 Caddy/Nginx 反代），也可以是内网 / 隧道
地址（如 Tailscale 的 `100.x.y.z`）——后者需在设置页显式勾选「允许明文 HTTP」，因为令牌会以明文
传输；公网地址一律要求 https。

切换模式会清空本地事件缓存与关注列表（两种模式使用不同的事件标识），筛选设置保留，
切换前会弹框确认。后端不可达时只显示缓存并给出明确错误，不会静默改走直连。

## 功能

- 五个一级导航：首页、日历、市场、历史、设置。
- 客户端直接获取 TradingView 财经日历 JSON 接口并写入 Room；直连时优先 IPv4，对持续网络失败使用持久化熔断，失败时对本周范围回退 Forex Factory，并遵守其 `Retry-After` 限流窗口。
- 客户端直接从 Yahoo Finance、Binance、BiQuote、CNBC 获取实时行情和可用的分钟 K 线。
- 在设备端计算 Actual / Consensus 预期差、规则信号、理论影响和实际行情窗口。
- 事件公布后可用自有 AI 接口生成市场分析：传导链路（数据意外 → 利率/美元/风险资产）、数据解读与走势判断，结果存入 Room，可随时重新生成。
- 首页下一重要事件、秒级倒计时、今日事件和市场概览。
- 七日选择、重要性与国家筛选的财经日历。
- 所有成功获取的事件都会合并入 Room；历史页每次从最新 8 条开始，滑到列表底部自动从数据库加载再早 8 条，本地事件最长保留 730 天。公开行情超出保留期时明确显示缺失，不使用当前价格伪造历史数据。后端模式下历史分页直接请求服务端。
- 所有时间按设备系统时区展示，每日区间（日历、首页今日、历史同步窗口）也按同一时区划分。
- Room 离线缓存与本地关注状态。
- English / 简体中文 / 繁體中文界面。
- 后端模式下的事件公布本地通知（应用运行时）。

## 翻译与 API Key

直连模式下应用默认固定为英文。设置页提供：

- OpenAI 兼容 API Key
- HTTPS API endpoint
- 模型名称

只有成功保存非空 Key（或处于后端模式）后，简体中文和繁體中文选项才可选择。事件名称由客户端直接调用用户配置的翻译接口；每批翻译完成后会再次交给模型校对，确认无误才会入库，未通过的条目会自动重译。翻译失败不会影响日历和行情数据。

事件名勘正：直连模式立即在本机生效；后端模式提交给服务器，由管理员在 Telegram 上审核后对所有设备生效。

设置页「测试连接」会调后端的 `/api/v1/meta`，校验地址、证书与协议版本。

安全措施：

- APK 与源码不包含用户 Key，也不内置后端地址与令牌。
- Key 与后端令牌使用 Android Keystore 的 AES-GCM 密钥加密后写入独立 SharedPreferences。
- 密钥文件排除云备份和设备迁移。
- 翻译 endpoint 默认必须使用 HTTPS，且不能包含用户信息、查询参数或 fragment；后端地址仅在内网/隧道 + 显式开启时才允许 HTTP。
- 调试日志只记录 HTTP BASIC 元数据，不记录 Authorization header。
- 建议使用有权限范围和消费限额的个人 Key；后端令牌由管理员发放并可单独吐销。

## 第三方数据源

直连模式只包含公开第三方服务入口，不包含自有服务器地址：

- `economic-calendar.tradingview.com`：财经日历（主源，免 Key JSON 接口）
- `nfs.faireconomy.media`：Forex Factory 本周财经日历（回退源，免 Key JSON）
- `query1.finance.yahoo.com`：传统市场行情
- `data-api.binance.vision`：Binance 公共加密资产行情
- `biquote.io`：外汇与贵金属行情
- `quote.cnbc.com` / `ts-api.cnbc.com`：美债收益率与 1 分钟 K 线（主源）

第三方服务可能限流、调整页面结构或缩短历史数据保留期。应用使用本地缓存、逐源降级和明确缺失状态，但不能保证第三方长期可用。使用时应遵守对应服务条款。

## 构建与测试

需要 JDK 17：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```
