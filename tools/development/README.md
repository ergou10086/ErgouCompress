# 后端开发与验收

S3–S5 已通过各阶段门；UI 集中开发暂缓。旧窗口保留 S2 演示能力，不作为新后端能力的验收入口。

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

每阶段通过测试后使用中文 Git 提交，正常 fast-forward push；不 force push。docs 与本地测试报告按项目约定不提交，运行/接口说明保留在此文件。

## S5 媒体策略与分析

通用模型/策略/参数帮助/估算/缓存编排位于 sharedLogic/commonMain；FFmpeg、磁盘缓存位于 jvmMain/infrastructure/analysis；AppWiring 只负责装配。没有改动 sharedUI 源码。领域测试使用端口 fake；真实媒体、原生提交和进程崩溃测试独立于 UI。

新任务 schemaVersion/plannerVersion=3。旧 v1/v2 的 canonical 字段集合及命令规划保留，不能把新字段塞进旧版计划。数据库升级 v3，新增派发索引并形成最低 reader 边界，防止旧 S4 程序忽略新 JSON 参数后误执行；v1/v2 升级均先一致性备份。原始探测 JSON 归档到工作区，工具自检增加真实 AAC 输出探测。

请求示例（请替换绝对路径）：

```json
{
  "inputs": ["D:/Media/input.mp4"],
  "ffmpeg": "D:/Tools/ffmpeg/bin/ffmpeg.exe",
  "ffprobe": "D:/Tools/ffmpeg/bin/ffprobe.exe",
  "outputDirectory": "D:/Output",
  "mode": "COPY",
  "settings": {
    "backend": "X264",
    "quality": 23,
    "container": "MKV",
    "operation": "ENCODE",
    "streams": [
      {"sourceIndex": 1, "action": "ENCODE", "aac": {"bitrateKbps": 192}},
      {"sourceIndex": 2, "action": "DROP"}
    ],
    "maxWidth": 1920,
    "maxHeight": 1080,
    "preserveMetadata": true,
    "preserveChapters": true
  }
}
```

sourceIndex 是源绝对流索引，不是“第几个音轨”。默认主视频 Encode、其他轨 Copy；所有原始流都需兼容或明确 Drop。只保留一个真实视频；字幕当前只 Copy/Drop（MKV ASS/SRT/WebVTT/PGS/VobSub，MP4 mov_text），字体附件仅 MKV TTF/OTF，不能冒充字幕格式转换。未知流/封面/HDR/交错/旋转等保持审核边界，不自动 8bit 化。音频 Encode 为 AAC，留空 sampleRate/channels 保持源规格；明确填写才重采样/降混，1–6 声道。失去默认音轨后，MP4 需为保留轨明确 `defaultDisposition: true`；这在规划时解决，禁止依靠 muxer 的隐式提升。

operation=REMUX 只 Copy/Drop，禁止缩放/FPS/码率或音频 Encode。maxWidth/maxHeight 是偶数上限、不放大，保持显示宽高比；frameRate 为 `{ "numerator": 30000, "denominator": 1001 }` 时显式 CFR，否则保留逐帧时间线。rateControl=BITRATE 必须填写 videoBitrateKbps（十进制 kbit/s）；QUALITY 不能带该字段。质量与码率均不承诺硬字节上限。默认保留标签/章节，字体的 mimetype/filename 是必要结构标签；明确删除普通 metadata 时仍保留这两项。

MP4 章节会生成独立 bin_data/text 章节辅助轨；源这类 data 仍必须明确 Drop，保留章节时输出只允许已知辅助轨布局并核对完整章节。不会把任意未知 data 当作章节放行。

```powershell
.\gradlew.bat :desktopApp:run --args="--analyze-job=任务ID"
.\gradlew.bat :desktopApp:run --args="--analyze-job=任务ID --sample --static"
```

AppWiring.analyze(plan, AnalysisOptions) 返回版本化 AnalysisReport。快速估算按所选视频/音频码率独立计数；固定质量无采样、复制码率缺失、保留附件/字幕预算未知时明确 UNKNOWN。可选采样默认 3×8 秒，上限 5×12 秒，短视频减少样本，和正式任务共用视频编码参数；只编码视频，用实际包字节去掉重复封装开销，再单独加所选音频预算。报告含样本位置、时长、耗时、包/文件字节、依据及启发式范围，不称为统计置信区间。RUNNING_PROGRESS 随 AttemptEvent 提供平滑预测，只有完整验证及发布后的 ACTUAL 才是实际大小。

缓存键含源强证据、完整 canonical 计划/工具指纹、分析版本和采样/静态选项；命中前后仍核对源和工具，更换参数/工具/输入必失效。派生缓存位于 analysis/cache，生成样片/分析帧位于 analysis/artifacts，与权威数据库/验证/提交日志隔离。静态提示用五个位置的低分辨率灰度差异，不检查全部帧、不自动改帧率或删运动。参数帮助 ParameterHelp 与条件推荐 MediaRecommendations 可供后续任意平台 UI 使用。

S5 修正了 MKV 毫秒时间基到编码器的精度碰撞：保留 FPS 时明确使用 demux 时间基；验证器逐个比较解码帧的相对时间戳（2 ms 容器量化容差）和帧数/首尾，不依赖容器 avg_frame_rate 证明 VFR。复制流核对包内容/数量/首尾和 extradata，AAC 核对规格、完整解码和时间线；字体核对内容 hash。完整压制不加入采样的 -ss/-t/-shortest。

改扩展名任务的“恢复原始版本”现在恢复原文件路径和扩展名；通过反向 CHANGE_EXTENSION 事务先独占发布，再把当前压缩版本归档为新备份。原路径已有外部文件会拒绝，旧唯一原始备份不消费。当前逆向恢复支持原扩展名 MP4/MKV；其他原扩展名保留原始备份并明确拒绝此自动恢复入口。与同名恢复一样沿用 journal 和实物对账。

测试记录：S3 52 项、S4 68 项通过。S5 最终阶段门与预测基准详见本地 docs/planning/09-s3-s5-development.md。测试覆盖 CPU/NVENC、AAC、Drop 后重映射、字幕字体、章节、缩放/CFR、分数 FPS/VFR、转封装、采样缓存和静态候选。S6 非 UI 实现见下节；生产 UI 与 S7 发布矩阵仍待完成。

命令语义参考：[FFmpeg 官方命令文档](https://ffmpeg.org/ffmpeg.html)、[滤镜文档](https://ffmpeg.org/ffmpeg-filters.html)。开发依赖版本沿用已锁定基线，不借本阶段升级依赖。

恢复操作在发布前停止会持久化 CANCELLED 并释放预留，启动不自动发布；发布门开始后完成不可取消的核对。S5 数据根禁止用 S4/更早程序降级打开。

最终 S5 回归 90 项（sharedLogic 54、desktopApp 36），0 失败、0 跳过；自带运行时包的 AAC 任务、持久历史、采样/静态分析及 v2→v3 数据升级冒烟均通过。历史原始测试产物按项目清理规范回收，阶段结论保留在 docs。

## S6 档案维护（无生产 UI）

数据库升级为 v4，新增 managed_artifact 与 cleanup_preview；v1/v2/v3 均先一致性备份后迁移，禁止旧程序降级打开。FrozenJob/planner 继续保持 v3 和既有 canonical，不因维护功能修改冻结参数。提交记录新增 backupReleased/backupEvidence，分别表达明确释放备份与迁移后的副本身份。

commonMain 的 ArtifactCleanup、StorageOverview、MaintenanceGate、SpaceAwareExecutor、DiagnosticRedactor 通过端口组合。JVM 负责 SQLite/JSON/文件和迁移，Windows 使用真实句柄删除，不将普通路径删除作为其他平台的默认安全能力。常规执行与分析共享活动门，清理/迁移/导出独占；迁移成功后旧会话封存。

```powershell
.\gradlew.bat :desktopApp:run --args="--storage"
.\gradlew.bat :desktopApp:run --args="--retention"
.\gradlew.bat :desktopApp:run --args="--retention-settings=保留策略.json"
.\gradlew.bat :desktopApp:run --args="--cleanup-preview --clear-cache --include-candidates"
.\gradlew.bat :desktopApp:run --args="--cleanup=预览ID --confirm=预览令牌"
.\gradlew.bat :desktopApp:run --args="--export-support=应用工作根外的新诊断包.zip"
.\gradlew.bat :desktopApp:run --args="--migrate-data-root=已存在的空目录"
```

每次只选择一个维护动作，可附加 `--data-root=绝对路径`。选择备份清理须在预览时加 `--include-backups`，执行时再加 `--ack-backup-loss`。预览有效 15 分钟且只能消费一次；执行重新核对引用、保留策略、路径、file ID 和 SHA-256。原始素材、工具、最终输出与审计元数据不可清理，活动/待核对任务、未决提交也会阻止清理。隐藏历史不解除保护。年龄从产物登记起算，不能用旧素材继承的修改时间绕过备份保留期。

默认保留策略：缓存 30 天、详细日志 14 天、缓存预算 512 MiB、原始备份最短 30 天；原始备份默认不删除，到期仍需明确选择。清理先事务标记，再按独占句柄删除并写墓碑；删除完成但落库失败由启动对账补记。离线或根身份改变不等于文件已删除。明确释放备份后仍可核对完成项，但拒绝该版本的恢复。媒体逻辑减少、备份保留和逻辑删除量分别统计，不承诺精确磁盘回收。

编码预算按实际卷合并，候选与 staging 各按源大小的两倍保守预留，每卷安全余量 64 MiB。运行中只监测安全余量；不足时先持久暂停，再停止编码并保留候选。该预算不承诺最终文件硬上限，也不替代运行监控。

数据根迁移要求所有任务终结、提交已核对、目标空且路径安全。复制并校验完整内容、权限、时间与 ADS，保留迁移前一致性数据库副本，重定位内部执行引用和备份身份，冻结源/工具/输出路径不变。根内已登记缓存也迁移；原生库临时解压文件重建。最后原子切换启动指针并要求重启。失败不切换源档案，未完成目标带 pending 标记不能启用；旧根保留回退，跨卷旧登记工作区继续服务原输出卷。此接口不能替代独立离线灾备产品。

诊断 ZIP 仅含计数和有界脱敏日志，匿名条目名，默认移除本地路径、素材名称、邮箱和凭证，不含数据库、素材或版本备份。每文件读取上限 256 KiB，总读取预算 4 MiB，导出目标不能覆盖已存在文件。审计凭据保留，详细日志按策略清理。

## 回归、清理与设计原型

```powershell
.\verification\verify-backend.ps1
.\verification\verify-backend.ps1 -Media -Prototype
```

真实媒体通过 PATH 或 ERGOU_TEST_FFMPEG/ERGOU_TEST_FFPROBE 选择工具；Prototype 临时安装 jsdom 26.1.0，需要 npm 可联网。脚本记录各套件简要结论到本地 docs/report/latest-backend-verification.md，然后清理当轮原始测试报告、编译后的测试类、临时依赖和缓存；系统临时文件集中到独占根，JVM 退出后再清理原生 DLL。合成媒体/SQLite 使用 JUnit TemporaryFolder，跨卷夹具使用 OwnedTestDirectory 并在退出时校验边界清理。失败的必要诊断进入断言，不额外复制长期日志。测试源码和可复用验证工具属于代码，长期保留；用户原片不属于清理目标。

项目中文注释与清理规范见根 AGENTS.md。独立 HTML 设计入口为 design/desktop-prototype/index.html，可直接双击离线打开，使用说明见同目录 README。没有改动生产 sharedUI；DOM 测试不证明浏览器视觉、原生焦点、DPI、万条清单性能或读屏体验通过。生产 UI 和 S7 完整发布验收继续单独推进。

S6 最终回归 112 项（sharedLogic 68、desktopApp 44，其中真实媒体 11），0 失败、0 跳过；HTML DOM 交互另有 6 项通过。自带运行时的空间/清理/诊断/迁移重启冒烟通过。原始输出已清理，简要验收结论保存在本地 docs。
