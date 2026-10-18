# 后端开发与验收

当前按 S3 → S4 → S5 逐阶段推进；UI 集中开发暂缓。旧窗口保留 S2 演示能力，不作为新后端能力的验收入口。

## 构建与测试

```powershell
.\gradlew.bat :sharedLogic:jvmTest :desktopApp:test
$env:ERGOU_TEST_FFMPEG = (Get-Command ffmpeg).Source
$env:ERGOU_TEST_FFPROBE = (Get-Command ffprobe).Source
.\gradlew.bat :sharedLogic:jvmTest :desktopApp:test -PmediaTests=true
.\tools\development\collect_test_results.ps1 -Name s3 -Modules sharedLogic,desktopApp
```

测试均在临时目录生成自己的字节/媒体夹具，不操作私有原片。媒体测试显式启用；缺设备按 skipped 报告，不能当通过。SQLDelight 查询与 schema 位于 sharedLogic/commonMain/sqldelight；领域不依赖生成的数据库类型。

## 无 UI 入口

```powershell
.\gradlew.bat :desktopApp:run --args="--history"
.\gradlew.bat :desktopApp:run --args="--recover"
.\gradlew.bat :desktopApp:run --args="--enqueue=绝对路径/frozen-job.json --run-job=任务ID"
.\gradlew.bat :desktopApp:run --args="--retry-item=任务ID:序号 --run-job=任务ID"
```

可以附加 `--data-root=绝对路径` 和 `--strict-portable`。冻结任务是带版本/hash/完整工具与源快照的 FrozenJob JSON，不能改几个字段后直接复用旧 hash。CLI 在同一数据根文件锁内先恢复再调度；不提供绕过工具自检、源身份或媒体验证的命令。

后端 API：`AppWiring.queue` 提供 submit/recover/run/pause/cancel/retry/history/items/attempts/events/hide。pause(false) 完成当前文件再停；pause(true) 先持久化停止请求再发 q；发布已开始时完成发布和核对后停止派发。取消不删除源、候选或已完成媒体。隐藏历史只改索引。崩溃后只有未完成项重跑，完成项内容变化进入 NeedsAttention。

## S4 提交与恢复

`AppWiring.prepareRequest(ProcessingRequest)` 通过真实工具验证/扫描创建冻结任务；`--request=请求.json` 创建 READY 任务并输出历史，附加 `--execute` 才执行。请求包含 inputs、ffmpeg/ffprobe、outputDirectory、settings、mode（COPY/REPLACE/CHANGE_EXTENSION）、onlyIfSmaller、maxDepth、minimumBytes。原地压制默认 onlyIfSmaller=true；格式调整允许更大必须明确设置 false。

开发原地 CLI 执行及恢复原始版本需要明确 `--allow-in-place`。这不跳过校验/备份门，也不代表 S7 产品发布放行。例：

```powershell
.\gradlew.bat :desktopApp:run --args="--request=请求.json --execute --allow-in-place"
.\gradlew.bat :desktopApp:run --args="--register-workspace=已创建的工作区目录"
.\gradlew.bat :desktopApp:run --args="--commits"
.\gradlew.bat :desktopApp:run --args="--restore=提交ID --allow-in-place"
```

CLI 最终报告同时写到数据根 diagnostics/backend-result.json；冻结计划及扫描未选原因写到 jobs/frozen。副本和原地提交统一走数据库日志与实物对账；同名替换必须带同卷备份，改扩展名先发布再归档，恢复复制备份而不消费唯一原始版本。数据根本卷自动登记；其他卷须明确登记工作区，盘符/卷身份变化阻止提交。只对本地 NTFS 开放；加密/压缩/稀疏文件仍需独立策略，不自动降级。

数据库 v1→v2 使用 VACUUM INTO 生成一致性备份后事务化迁移，原 FrozenJob 快照与 plannerVersion=1 的 canonical/hash 保持兼容。目标、原始备份、DACL 语义、创建/修改时间及 ADS 有实机测试。启动对已完成项只审计，不恢复外部修改的权限或内容。S4 崩溃测试在八个检查点真实 halt 私有子 JVM；错误码注入不是物理磁盘满/断电验收。

## S4 提交与恢复

`AppWiring.prepareRequest(ProcessingRequest)` 通过真实工具验证/扫描创建冻结任务；`--request=请求.json` 创建 READY 任务并输出历史，附加 `--execute` 才执行。请求包含 inputs、ffmpeg/ffprobe、outputDirectory、settings、mode（COPY/REPLACE/CHANGE_EXTENSION）、onlyIfSmaller、maxDepth、minimumBytes。原地压制默认 onlyIfSmaller=true；格式调整允许更大必须明确设置 false。

开发原地 CLI 执行及恢复原始版本需要明确 `--allow-in-place`。这不跳过校验/备份门，也不代表 S7 产品发布放行。例：

```powershell
.\gradlew.bat :desktopApp:run --args="--request=请求.json --execute --allow-in-place"
.\gradlew.bat :desktopApp:run --args="--register-workspace=已创建的工作区目录"
.\gradlew.bat :desktopApp:run --args="--commits"
.\gradlew.bat :desktopApp:run --args="--restore=提交ID --allow-in-place"
```

CLI 最终报告同时写到数据根 diagnostics/backend-result.json；冻结计划及扫描未选原因写到 jobs/frozen。副本和原地提交统一走数据库日志与实物对账；同名替换必须带同卷备份，改扩展名先发布再归档，恢复复制备份而不消费唯一原始版本。数据根本卷自动登记；其他卷须明确登记工作区，盘符/卷身份变化阻止提交。只对本地 NTFS 开放；加密/压缩/稀疏文件仍需独立策略，不自动降级。

数据库 v1→v2 使用 VACUUM INTO 生成一致性备份后事务化迁移，原 FrozenJob 快照与 plannerVersion=1 的 canonical/hash 保持兼容。目标、原始备份、DACL 语义、创建/修改时间及 ADS 有实机测试。启动对已完成项只审计，不恢复外部修改的权限或内容。S4 崩溃测试在八个检查点真实 halt 私有子 JVM；错误码注入不是物理磁盘满/断电验收。

每阶段通过测试后使用中文 Git 提交，正常 fast-forward push；不 force push。docs 与本地测试报告按项目约定不提交，运行/接口说明保留在此文件。
