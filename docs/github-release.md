# GitHub Actions 统一 Release 与客户端更新

工作流：`.github/workflows/android-release.yml`。

- **Tag 自动发布**：推送 `vX.Y.Z` 自动运行 **Unified Release**，校验 Tag 与客户端版本及三语说明，分别测试构建签名 APK 和同一提交的 Linux x86_64 glibc 后端；两者全部成功后才发布同一个 Release。
- **手动运行**：Actions → Unified Release → Run workflow。选择 Tag 时同样发布；选择分支时只构建 Artifact，不创建 Release，也不部署后端。
- Android/后端构建 Artifact 分别为 `unified-android-<run_id>`、`unified-backend-<run_id>`；完整校验后的发行 Artifact 为 `unified-release-<run_id>`，手动分支构建也会生成。APK 使用 `macro-research-<versionName>.apk` 文件名。
- 发布先创建 draft，完整上传并重新校验附件后才转为正式 Release。已发布且相同提交、附件完整一致时重跑无操作；不完整、提交不同或校验失败时拒绝覆盖，必须使用新版本/新 Tag。未发布的同提交 draft 可重跑恢复。
- `.github/workflows/backend-release.yml` 仅保留分支/PR 测试构建、手动 Artifact 和 main 自动部署，不单独创建 Release。统一发行后端包版本与客户端一致，但不修改 Rust Cargo crate 版本。

## 首次配置签名

在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 添加：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Release keystore 文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 该别名对应的密钥密码 |
| `GOOGLE_SERVICES_JSON_BASE64` | `android/app/google-services.json` 的 Base64 编码 |

两种构建方式都需要配置全部五项；缺少任意一项会明确报错，而不是发布无法安装的 unsigned APK。
`GOOGLE_SERVICES_JSON_BASE64` 用于在 runner 临时恢复 Firebase 配置，构建结束时自动删除。

如果已有正式签名密钥，请继续使用同一份并安全备份。**不要为每次构建生成新密钥**，否则旧版本无法被新版本覆盖安装。不要把 keystore 或密码提交到仓库。

没有密钥时，可在仓库外创建一次（交互式输入密码）：

```bash
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/macro-research-release.jks" \
  -alias macro-research -keyalg RSA -keysize 3072 -validity 10000
```

Linux 下编码：

```bash
base64 -w 0 "$HOME/macro-research-release.jks"
```

Windows PowerShell 下编码到剪贴板（避免把私钥内容写入命令日志）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\macro-research-release.jks")) | Set-Clipboard
```

将结果粘贴为 `ANDROID_KEYSTORE_BASE64` Secret，其他三项填写对应值。签名文件在 runner 临时目录创建，构建步骤退出时删除，不上传为附件。

## Firebase 配置

在 Firebase 控制台为包名 `com.macroresearch` 添加 Android 应用并下载
`google-services.json`。不要提交该文件；将它编码为仓库 Secret：

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("android/app/google-services.json")
) | Set-Clipboard
```

将剪贴板内容保存为 `GOOGLE_SERVICES_JSON_BASE64`。工作流会在 `android/app/` 临时恢复该文件，
启用 Google Services 插件并在构建结束时删除。后端使用的 Firebase service-account JSON 是另一份
包含私钥的文件，不能放入 Android Secret 或 GitHub 仓库。

## 人工维护三语更新说明

唯一内容源是 `android/release-notes/<versionName>.json`，例如：

```json
{
  "schemaVersion": 1,
  "versionName": "x.y.z",
  "notes": {
    "en": {"client": ["Client changes"], "backend": []},
    "zh-CN": {"client": ["客户端变更"], "backend": []},
    "zh-TW": {"client": ["用戶端變更"], "backend": []}
  }
}
```

每种语言 client 至少一条；无后端行为变化时 backend 留空。每组最多 30 条、每条最多 1000 个 UTF-16 字符，内容为纯文本，不能依赖链接或 HTML 才能理解。请人工审阅，不自动从提交记录或模型生成说明。

Gradle 校验语言、版本和长度，添加 versionCode 后生成 `assets/release-notes.json`，Debug/Release 均打包。缺文件或格式错误会使构建失败。Action 从实际 APK 提取同一文件，用于发布附件、更新清单和三语 Release 正文，不使用 GitHub 自动提交说明。

客户端全新安装/覆盖升级后第一次打开，无需联网即可展示当前版本说明；点击“知道了”才记录确认。跨版本升级只显示当前版本，不汇总跳过的版本。确认状态不备份、不迁移；设置页可随时重看。

## 发布版本

先修改 `android/app/build.gradle.kts` 的 `versionCode` 和 `versionName`。**Tag 名不会自动修改 APK 内部版本**，每个新版本应递增 `versionCode`，以允许覆盖升级。

每次发布时，根据本次改动确定新的版本号：更新 `versionName`，并将 `versionCode` 增加（必须高于已发布版本）。提交并推送后，将下面的 `VERSION` 替换为与 `versionName` 一致的版本号，例如 `x.y.z`：

```bash
VERSION="x.y.z"
git tag -a "v${VERSION}" -m "Macro Research ${VERSION}"
git push origin "v${VERSION}"
```

推送匹配的新 Tag 即触发自动构建发布；失败时可在 **Actions → Unified Release → Run workflow** 选择该 Tag 重试。不要对已经发布的 Tag 移动提交或替换二进制。手动选择分支仅构建 Artifact。正式发布前还必须新增与 versionName 对应的三语说明文件。

工作流使用 JDK 17、Android SDK 和仓库内的 Gradle Wrapper，执行：

```bash
bash ./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

成功后，GitHub Release 包含：

- `macro-research-<versionName>.apk`：正式签名 APK。
- `market-event-analyzer-<versionName>-x86_64-unknown-linux-gnu.tar.gz` 和 `.tar.gz.sha256`：同提交后端及其校验和（Ubuntu 22.04/glibc 2.35+）。
- `release-notes.json`：与 APK 中一致的三语说明。
- `update.json`：schemaVersion、packageName、versionCode/versionName、minSdk、apkAssetName/apkSize/apkSha256、releaseNotes。
- `release.json`：统一版本、gitCommit、附件 name/size/sha256 清单（不含自身及总校验文件）。
- `SHA256SUMS.txt`：全部数据附件的校验和，不包含自身。

包名、版本、minSdk 从实际 APK 读取，不从 Tag 推测；后端包通过显式 `--version` 指定统一发行版本，其 README 记录同一 Git 提交，只包含安全配置模板。正式 Release 必须公开，客户端不嵌入 GitHub Token。

发布辅助工具为 `scripts/release_tools.py`；可用 `python3 -m unittest discover -s scripts -p 'test_*.py'` 验证契约和发布幂等性。

发布使用 GitHub 自带的 `GITHUB_TOKEN`，仅发布任务请求 `contents: write` 权限，无需配置 PAT。若组织策略限制 Actions 写权限，需由管理员放行。

## 安装与本地构建

当前手机若装的是 Debug 签名版本，正式 Release 通常不能直接覆盖安装（签名不同）。请先备份需要的数据，再自行卸载 Debug 版本；卸载会删除应用本地事件和设置。后续只要使用同一正式密钥和更高 `versionCode`，即可覆盖升级。

本地默认 `assembleRelease` 仍生成 unsigned APK。需要本地正式签名时，设置以下环境变量后再构建：

- `ANDROID_KEYSTORE_PATH`：keystore 的绝对路径。
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

签名不改变 `applicationId`，也不会把用户配置的 AI API Key 打包到 APK。

## 应用内检查与安装

设置页可手动检查或打开 GitHub 发布页。正式版默认每日前台检查一次（可关闭），Debug 默认关闭；后台不定时唤醒，未确认不会下载或安装。检查失败、限流或 GitHub 不可达不影响宏观数据功能。只接受完整正式统一 Release，忽略 draft、预发布及缺新清单的旧 Release；没有合格发布会明确提示，而不是声称最新版。

用户确认后由 Android DownloadManager 下载，支持后台继续、前台进度与取消/重试；不允许漫游。APK 写入应用专属目录，最多 512 MiB，完成后复制到私有缓存校验 SHA-256、包名、版本、最低系统版本和当前安装签名。签名轮换首版不支持；Debug/正式签名不匹配时拒绝安装，不引导卸载或清数据。哈希不替代签名身份校验。

只有用户点击安装才请求“允许此来源安装应用”，返回后再次核验 APK，通过限定缓存目录的 FileProvider 授予临时读取权限并调用系统安装器。下载完成不会后台弹出安装器；拒绝授权或取消安装均可重试。更新偏好及外部 APK 排除备份；安装后清理旧下载，缓存被清理时需重新下载。
