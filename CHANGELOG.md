# 更新日志

## 1.2.0

- Android 版本：`1.2.0`，`versionCode 19`；发布 Tag：`v1.2.0`。
- 直连日历优先使用 BiQuote，改进多源事件合并及时间、数值和单位保留。
- 个人 AI 分析由客户端执行，新增只读证据工具、执行时间线、请求日志与分析历史。
- 新增 BiQuote 新闻、文章预览、Google News 链接解析、默认浏览器打开及手动补充上下文。
- 后端增加 BiQuote 日历支持，保持 TradingView 优先，并改进事件身份与观测记录保留。
- TypeSafe 增加有限重试和底层网络诊断，失败时仍安全兜底；新增 Telegram 事件查询及 FCM 诊断。
- **兼容性提示**：共享后端 AI 分析接口已移除，请在客户端配置个人模型接口；升级无需卸载或清除本地数据。

完整三语更新说明及应用内更新内容以 [android/release-notes/1.2.0.json](android/release-notes/1.2.0.json) 为准。签名 APK 与同提交 Linux 后端包由统一发布工作流生成。
