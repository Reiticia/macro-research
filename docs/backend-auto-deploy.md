# 后端推送后自动部署（SSH + tmux）

## 工作流结构

- 构建：`.github/workflows/backend-release.yml`。
- 部署：独立的可复用工作流 `.github/workflows/backend-deploy.yml`，仅提供 `workflow_call`。
- 构建工作流最后的 `deploy` job 使用 `needs: build` 和 `uses: ./.github/workflows/backend-deploy.yml` 引用部署工作流。
- **仅推送 `main` 且测试、构建、上传 Artifact 全部成功后自动部署**。PR、其他分支、Tag 和手动构建不会部署。
- 使用本次构建运行的 Artifact，不下载 GitHub Release 中的「最新」附件。已不是 `main` 最新提交的构建会跳过部署。
- 部署串行执行，禁止中途取消；服务器另外使用 `flock` 防止多个部署脚本同时改动安装目录。

## 服务器前提

安装目录固定为 `/root/macro-research`，保留现有结构：

```text
/root/macro-research/
├── backup.sh
├── config.toml
├── data/market.db
├── firebase-service-account.json
├── market-event-analyzer
├── README.txt
└── rules.toml
```

服务器需要 Linux x86_64、glibc >= 2.35，以及 `bash`、`tmux`、`sqlite3`、`tar`、`sha256sum`、`flock`、`tee`、`cmp` 等常规工具。例如 Debian/Ubuntu：

```bash
apt-get update
apt-get install -y tmux sqlite3 coreutils diffutils util-linux tar
```

现有 `backup.sh` 必须能执行：

```bash
cd /root/macro-research
bash ./backup.sh --dir /root/macro-research
```

自动部署会先停止 tmux 中的旧后端，再执行备份，避免后端持续写入造成 `database is locked`。推荐继续使用 SQLite `.backup` 创建一致性副本，部署脚本也要求安装 `sqlite3` 以支持数据库完整性检查。请确保没有其他服务或手工命令同时写入该数据库。仓库目录中的独立 `backup.sh` 若未安装，不会由工作流覆盖或上传；部署调用的是服务器现有版本。

当前后端必须是 tmux 中**交互式 shell 的前台进程**，例如在该窗格中执行：

```bash
cd /root/macro-research
./market-event-analyzer
```

默认目标是 `macro-research:0.0`（会话名、窗口索引、窗格索引），不是任意一个当前活动窗格。确认目标：

```bash
tmux list-panes -a -F '#{session_name}:#{window_index}.#{pane_index} #{pane_current_command}'
```

不能把二进制直接作为 tmux 窗格的初始命令并替代 shell，否则 Ctrl+C 会关闭整个窗格；这类情况部署脚本会拒绝处理。目标窗格必须专用于后端，自动部署期间不要在其中交互。

## GitHub Secrets / Variables

在仓库 **Settings → Secrets and variables → Actions** 配置 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `BACKEND_DEPLOY_HOST` | 服务器 IP 或主机名，不带 `ssh://` 或端口 |
| `BACKEND_DEPLOY_SSH_KEY` | 专用、无口令的 OpenSSH 私钥全文 |
| `BACKEND_DEPLOY_KNOWN_HOSTS` | 已核对服务器指纹的 known_hosts 条目全文 |
| `BACKEND_DEPLOY_PORT` | 可选，默认 `22` |
| `BACKEND_DEPLOY_USER` | 可选，默认且要求为 `root`，用于访问 `/root/macro-research` |

可选 Repository variables：

| Variable | 默认值 | 用途 |
| --- | --- | --- |
| `BACKEND_TMUX_PANE` | `macro-research:0.0` | 明确选择后端所在窗格 |
| `BACKEND_DEPLOY_OBSERVE_SECONDS` | `60` | 新进程持续运行观察秒数，允许 `30..600` |

建议生成专用部署密钥，在服务器 `/root/.ssh/authorized_keys` 添加其公钥，仅把私钥放入 Secret。不要提交私钥、服务器配置或 Firebase 文件。该密钥有服务器 root 权限，应严格限制仓库写入权限和 Secret 管理权限。

**不能使用 `StrictHostKeyChecking=no`**。可先获取候选条目：

```bash
ssh-keyscan -p 22 YOUR_SERVER > backend-known-hosts
ssh-keygen -lf backend-known-hosts
```

再通过服务器控制台核对实际主机公钥指纹，例如：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

确认候选指纹与服务器实际一致后，才把条目放入 `BACKEND_DEPLOY_KNOWN_HOSTS`。非默认端口使用 `[主机]:端口` 的 known_hosts 条目。工作流严格校验主机密钥，并在结束时删除 runner 中的 SSH 凭据。

## 首次启用：确认配置模板基线

服务器的真实 `config.toml` 有密钥及自定义值，**不能拿它与模板直接比较**。自动部署使用 `.deploy-config-example.sha256` 记录「已人工确认兼容的配置模板版本」，缺少基线也会跳过部署，避免第一次运行就误升级未知版本。

先确认服务器当前二进制对应的 Git 提交，并人工核对该提交的 `backend/config.example.toml` 与服务器配置兼容。然后在有仓库的本地机器运行：

```bash
# 必须填服务器当前已人工部署、配置也已核对的提交，而不是随意用最新 main。
DEPLOYED_SHA=YOUR_MANUALLY_DEPLOYED_COMMIT
git show "${DEPLOYED_SHA}:backend/config.example.toml" | sha256sum
```

取输出中的 64 位哈希，在**服务器**保存基线及部署记录：

```bash
cd /root/macro-research
printf '%s\n' 'YOUR_VERIFIED_TEMPLATE_SHA256' > .deploy-config-example.sha256
printf '%s\n' 'YOUR_MANUALLY_DEPLOYED_COMMIT' > .deploy-revision
chmod 600 .deploy-config-example.sha256 .deploy-revision
```

这些都是非敏感的部署元数据，不修改 `config.toml`。完成 SSH Secrets、tmux 目标和基线设置后，下一次 `main` 后端推送即可自动部署；也可以重新运行当前最新提交的 push 构建。

## 自动部署步骤与失败表现

1. 检查构建仍是 `main` 最新提交；校验 SSH 凭据及服务器主机密钥。
2. 比较本次 `config.example.toml` 的 SHA256 与服务器基线。不同或缺失时，发出 Actions warning 和 Summary，**跳过上传、备份、替换和重启**，要求人工部署。
3. 下载同一次运行的构建包，验证随包校验和，上传到服务器临时目录。
4. 服务器加部署锁、再次验证配置基线，检查指定 tmux 窗格及旧后端 PID。先验证产物 SHA256，并在临时目录**只解压二进制**，不修改现有可执行文件，不停服。
5. **停止旧后端**：对指定窗格发送 Ctrl+C，最多等待 30 秒停止，确认后端已退出且返回交互式 shell；停止失败则不进行备份和替换。
6. **备份**：严格执行 `bash ./backup.sh --dir /root/macro-research`。失败时不覆盖二进制，并尝试在原窗格重启、验证未修改的旧后端；即使恢复成功，部署节点仍报错。恢复失败会明确提示人工处理。
7. **替换二进制**：备份成功后原子替换已停止的后端文件。`config.toml`、`rules.toml`、数据库、Firebase JSON、README 和 `backup.sh` 不覆盖。若此次规则文件也需更新，请人工处理。
8. **启动新后端**：在**同一窗格**从 `/root/macro-research` 启动新二进制，显式指定现有 `APP_CONFIG`。控制台输出仍出现在 tmux 中，同时保存到 `.deploy-run-<commit>.<随机值>/backend.log`。
9. 校验新 PID、实际可执行文件路径及 tmux 归属，持续观察默认 60 秒，并用 `/proc` 的启动时间防止 PID 重用造成误判。旧进程仍存活、没有启动新进程、启动后立即退出或观察期间退出，都会使 **「停止旧后端、备份、替换并验证新后端持续运行」节点报错**。
10. 成功后更新 `.deploy-revision` 和配置基线；失败不记录为成功。临时上传文件会被清理，备份与本次服务器日志保留。

Telegram 启动通知会显示 `Git 提交 <完整 hash>`。该值在编译时从本次 GitHub Actions 的提交中嵌入二进制，可直接核对是否运行了本次构建；不再显示不会随每次提交变化的 Cargo 版本号。

这里验证的是指定新二进制**进程持续存活**，并不是长期服务监控，也不替代 HTTP/TLS 健康检查。观察窗口结束后仍建议用 Telegram 启动通知、API 或外部监控确认业务功能。

日志只保留在服务器，不上传含有运行时信息的日志到 Actions。失败时可通过 `tmux a -t macro-research` 查看控制台，或读取对应的 `.deploy-run-*/backend.log`。每次替换也在该目录保留 `previous-binary`。

**替换二进制后不自动回滚数据库或旧二进制**：新程序启动时可能已经执行 SQLite 迁移，盲目回滚二进制可能不兼容新库。请依据备份包，人工决定是否恢复二进制、配置和数据库的同一版本。仅在替换之前发生失败、且原二进制确实未改变时，脚本才尝试重新启动旧后端，这不是数据库回滚。

## config.example.toml 变化后

自动部署比较的是**上次已确认部署的模板基线**，不是仅比较当前推送的最后一条提交。因此某次模板变更被跳过后，后续普通代码推送也不会绕过这个保护。

需要人工核对并调整服务器配置，然后按停止旧后端、备份、解压并替换、重启和检查的顺序部署。确认该提交已成功运行后，按「首次启用」中的步骤写入该提交的模板哈希和提交号。不要只更新哈希来强行绕过检查。

本地脚本回归测试（不连接服务器）：

```bash
bash -n backend/deploy/auto-deploy.sh
bash backend/deploy/test-auto-deploy.sh
```
