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

每阶段通过测试后使用中文 Git 提交，正常 fast-forward push；不 force push。docs 与本地测试报告按项目约定不提交，运行/接口说明保留在此文件。
