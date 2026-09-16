# Macro Research

宏观财经事件研究工具。Android 客户端默认**直连公开数据源**，也可以切到**自建后端模式**：
所有数据、事件名翻译与 AI 分析由一台服务器提供，数据源出问题时再用 Telegram 机器人通知管理员。

```text
macro-research/
├── android/   Kotlin、Jetpack Compose、Room 客户端
├── backend/   Rust、Axum、SQLite 的统一下载与告警服务（可选）
└── docs/      客户端数据流与后端部署说明
```

## 两种数据来源

| | 直连（默认） | 后端 |
|---|---|---|
| 谁去抓数据 | 设备直接请求公开源 | 服务器统一抓取并缓存 |
| 受限网络 | 设备侧自备 HTTP 代理 | 服务器侧出网代理 |
| AI 分析与翻译 | 用户自己的 Key | 服务端统一 Key，免填 |
| 译名勘正 | 本机生效 | 提交管理员审核后对所有人生效 |
| 事件公布提醒 | 无 | WebSocket 本地通知 |

切换模式会清空本地事件缓存与关注列表（两种模式的 ID 空间不同），筛选设置保留。

## 数据流（直连模式）

- 财经日历：TradingView 经济日历 JSON 接口，失败时回退 Forex Factory 公开 JSON
- 美债收益率：CNBC 报价与 1 分钟 K 线，失败时回退 Yahoo Finance
- 股票指数、外汇与贵金属：BiQuote，失败时回退 Yahoo Finance
- 加密资产：Binance
- 可选 HTTP 代理：仅用于日历与行情请求，便于在受限网络下访问上述源；AI 与翻译请求不经代理
- 事件分析：设备端规则引擎
- AI 市场分析：用户配置的 OpenAI 兼容接口，事件公布后生成传导链路与走势分析，本地缓存且可重新生成
- 缓存：Room 本地数据库
- 事件名称翻译：用户自行配置的 OpenAI 兼容 API

APK 中不包含私人 API Key 或项目服务器地址。未配置翻译 Key 且未启用后端模式时，应用固定使用
英文；Key 由 Android Keystore 加密保存后才会解锁简体中文和繁体中文（后端模式下无需个人 Key）。

## 后端模式

后端提供统一的 REST + WebSocket 接口、服务端 AI 简报与翻译、访问令牌与每日配额，并在
数据源连续失败、限流或公布值长期缺失时用 Telegram 通知管理员，译名勘正也由管理员在
Telegram 上点按钮审核。**后端自己就能终结 TLS**（`[server] tls_cert` / `tls_key`），
不需要反向代理；证书可以用长期有效的 Cloudflare Origin Certificate。
部署与接口细节见 [后端说明](docs/backend.md)。

Android 侧在「设置 → 数据来源 → 后端」填 `https://…` 地址与管理员发放的令牌，点「测试连接」
校验后即可使用；内网/隧道地址可在显式开启明文开关后使用 `http://…`。

## 构建

需要 JDK 17 和 Android SDK：

```bash
cd android
./gradlew assembleDebug
```

后端需要 Rust 1.85+：

```bash
cd backend
cargo test
cargo run
```

详见 [Android 客户端说明](android/README.md)、[客户端架构](docs/client_architecture.md) 与
[后端说明](docs/backend.md)。

## GitHub Release 自动构建

支持 Actions 手动构建签名 APK，以及推送 Tag 时自动发布到 GitHub Releases。
首次使用需配置四项签名 Secrets，详见 [Release 构建与发布说明](docs/github-release.md)。
后端二进制由 `.github/workflows/backend-release.yml` 构建。
