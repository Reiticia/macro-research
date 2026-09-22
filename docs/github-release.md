# GitHub Actions Release 构建

工作流：`.github/workflows/android-release.yml`。

- **手动构建**：Actions → Android Release → Run workflow，选择分支或 Tag。成功后从该次运行的 `macro-research-release` Artifact 下载 APK，不创建 GitHub Release。
- **Tag 构建**：推送 Tag 不会自动运行；在 Actions → Android Release 对该 Tag 手动 Run workflow 后，测试、lint、构建签名 APK，并创建同名 GitHub Release，上传 APK 和 SHA-256 校验文件。
- 重跑已发布 Tag 的构建会覆盖同名附件，保留原 Release 说明。正式版本推荐使用新 Tag，避免覆盖已分发的安装包。

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

## 发布版本

先修改 `android/app/build.gradle.kts` 的 `versionCode` 和 `versionName`。**Tag 名不会自动修改 APK 内部版本**，每个新版本应递增 `versionCode`，以允许覆盖升级。

例如，把 `versionName` 改为 `0.3.0` 并递增 `versionCode`，提交并推送后：

```bash
git tag -a v0.3.0 -m "Macro Research 0.3.0"
git push origin v0.3.0
```

推送 Tag 不会触发自动构建；随后到 **Actions → Android Release → Run workflow** 选择刚推送的 Tag 手动运行。

工作流使用 JDK 17、Android SDK 和仓库内的 Gradle Wrapper，执行：

```bash
bash ./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

成功后，GitHub Release 包含：

- `macro-research-release.apk`：正式签名安装包。
- `SHA256SUMS.txt`：APK 校验和。

发布使用 GitHub 自带的 `GITHUB_TOKEN`，仅发布任务请求 `contents: write` 权限，无需配置 PAT。若组织策略限制 Actions 写权限，需由管理员放行。

## 安装与本地构建

当前手机若装的是 Debug 签名版本，正式 Release 通常不能直接覆盖安装（签名不同）。请先备份需要的数据，再自行卸载 Debug 版本；卸载会删除应用本地事件和设置。后续只要使用同一正式密钥和更高 `versionCode`，即可覆盖升级。

本地默认 `assembleRelease` 仍生成 unsigned APK。需要本地正式签名时，设置以下环境变量后再构建：

- `ANDROID_KEYSTORE_PATH`：keystore 的绝对路径。
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

签名不改变 `applicationId`，也不会把用户配置的 AI API Key 打包到 APK。
