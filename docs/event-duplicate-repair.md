# 历史重复事件修复（离线、人工批准）

工具：`backend/scripts/repair_event_duplicates.py`，Python 3.11+，仅标准库。

## 2026-11-07 报告的结论与修复范围

报告含美国 91 组候选、112 条多余记录，其中 86 组标为高置信、5 组待复核。它是候选审计，不是删除清单。

源码确认：

- 数据库仅按 `(provider, provider_id)` 唯一；短/长上游 ID 各自存在时可以保留多个历史行。当前版本已有同名、同国家、同精确时刻的新 ID 去重，但不会自动归并已经存在的行。
- 已绑定身份优先查回原行；多个历史候选也刻意不猜选归属。因此升级去重逻辑不会自动清除报告里的存量重复。
- 原有英文别名表未涵盖不少报告中的异名指标。
- 报告显示 TradingView ID 体系变化的线索，但没有服务器运行版本和采集日志，不能断言是哪个历史版本或哪次采集引入了重复。

本次在线修复增加 35 对来源/国家限定的别名回归用例，涵盖 ADP、贸易差额、ISM 分项、部分 EIA 指标、非农、PPI、零售及讲话等。月度指标可对照明确统计月份；周度不同日期不归并。SQL 候选查询兼容等价时区表示，再由 Rust 精确比较（不放宽到分钟，也不丢弃纳秒差异）。

没有把“报告高置信”直接等同于无限期通用别名：缺阶段的耐用品、生产率、单位劳动成本、笼统的 `Interest Rate`、`Goods Trade Balance` 等仍需额外来源证据。密歇根继续要求原有明确月份校验。报告的 5 组待复核以及时间冲突不自动处理。不同单位的 Actual 仍须通过原有换算检查。

## 事前准备

1. 先部署带本次修复的后端，让迁移 **031** 创建空审计/旧 ID 映射表；迁移本身不合并数据。
2. 安排维护窗口，停止后端及所有采集、回填、分析任务，确认没有其他数据库写入进程。`--service-stopped` 只是操作者确认标记，不能代替实际停服。
3. 保留现有完整备份。准备足够磁盘空间：预演会在临时目录复制整个 SQLite 数据库（不把行情库全放内存），正式执行还会创建完整备份；可用 `TMPDIR` 指向有空间的磁盘。
4. 以下命令从仓库根目录运行；报告目录需人工上传到服务器。不要上传数据库、计划或归档到外部模型服务。

## 生成计划（不批准、不修改业务库）

```bash
python3 backend/scripts/repair_event_duplicates.py \
  --database /root/macro-research/data/market.db \
  --prepare-report event-duplicates-20261107/us-duplicates.json \
  --plan /root/event-merge-plan.json
```

仅把报告中“高置信重复”组转成**未批准**模板，并从实时数据库读出完整行快照。若报告身份/时间已变或 ID 已不存在，停止并重新审计，不能把过期报告强套到新数据。

逐组人工检查：原始英文、来源、发布时刻、统计期、初终值、季调、单位、数值修订及关联数据。只对已核实的组填写：

- `approved: true`
- `keep_id`: 从该组 ID 中明确选择保留的一行；优先考虑客户端关注和有可靠数值/分析的记录，而不是机械保留最大或最小 ID。
- `reason`: 核实依据，以及为什么保留该行的数值/单位/预期基准。

其他组保持 `approved: false`。**不要修改 `records` 快照，不要把所有组批量批准。** 两行数值冲突时工具不替你判断；保留行所有字段原样保留，另一行的数值及单位进入审计归档。保留行缺 Actual 而其他行有值时拒绝执行。

## 预演与执行

```bash
# 默认只在临时副本模拟完整关联迁移；业务库以 mode=ro 打开。
python3 backend/scripts/repair_event_duplicates.py \
  --database /root/macro-research/data/market.db \
  --plan /root/event-merge-plan.json

# 只有确认预演无 blocked，且服务确实停止后，才执行。
# 备份路径必须不存在；不会覆盖已有备份。
python3 backend/scripts/repair_event_duplicates.py \
  --database /root/macro-research/data/market.db \
  --plan /root/event-merge-plan.json \
  --apply --service-stopped \
  --backup /root/market-before-event-merge.db
```

安全边界：

- 使用 SQLite backup API，包含已提交 WAL 数据；不直接复制一个可能未 checkpoint 的 `.db` 文件。正式执行先取得写锁，再从另一个只读连接备份。
- `BEGIN IMMEDIATE` 下再次比较所有计划行的完整快照；变化即拒绝，防止审查后又被采集更新。
- 国家、精确时点、公开来源必须满足；明确统计期字符串冲突或明确初终值冲突拒绝。离线工具比在线规则更保守：统计期格式不同但语义相同也需重新核实，不盲目丢弃日期。
- 迁移来源别名、观测、行情快照、市场反应、规则分析、AI 分析、AI 任务、标的选择、描述、缺值通知和 LLM 用量中的 `event_id`。
- AI 分析 ID 不变，反馈及反馈通知仍关联原分析；不会替换分析或丢掉反馈。
- **任一唯一约束冲突整组阻止**，例如两行都有同语言 AI 分析、同标的行情反应、同时间观测或事件描述。不会 `REPLACE`、`IGNORE`、覆盖或级联删除冲突记录。未覆盖的新事件引用表也拒绝处理。
- 任何已批准组被阻止时，正式执行整体不提交。可取消该组批准，单独调查，重新预演其余已核实的组。不要为通过检查而先删关联记录。
- 所有检查通过才提交；执行前后校验外键及数据库完整性。重复执行过期计划会拒绝，不会再次删数据。

## 验证、追溯和回滚

1. 保存执行输出，核对 `removed_ids` 与事件数减少量；重新运行只读审计。不要把剩余候选误报为已解决。
2. `event_duplicate_merge` 保存 `old_id → canonical_id`、批准理由、时间以及被移除事件/迁移前关联行的 JSON 归档。后续合并会把旧映射展平到最终保留 ID。
3. 旧 provider ID 查询仍有效；旧数字事件 ID 的详情、刷新、分析、行情 API 会解析到保留 ID，鉴权不变。客户端应使用响应中的规范 ID，重新同步列表；已缓存的旧列表不会凭空刷新。
4. 归档保留旧数值、精度、单位和来源，不将其冒充保留事件的新 Actual。分析正文内嵌的历史 JSON 不重写。
5. 重启后端，检查日历、详情、观测、分析、行情及采集日志；确认未重新插入相同来源别名。
6. 如需回滚，立即停止全部写入，保留当前数据库及 WAL/SHM 的取证副本，恢复完整备份。**不要把旧 WAL/SHM 与恢复后的备份混用。** 重启前检查完整性；整库恢复会丢失备份之后的新业务写入，不能在运行中覆盖数据库。

测试：

```bash
python3 -m unittest discover -s backend/scripts -p 'test_repair_event_duplicates.py' -v
cd backend
cargo test --test event_duplicate_audit --test event_deduplication --test unified_backend
```
