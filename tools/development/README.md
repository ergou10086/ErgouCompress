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

测试记录：S3 52 项、S4 68 项通过。S5 最终阶段门与预测基准详见本地 docs/planning/09-s3-s5-development.md 和 test-output/s5-*。测试覆盖 CPU/NVENC、AAC、Drop 后重映射、字幕字体、章节、缩放/CFR、分数 FPS/VFR、转封装、采样缓存和静态候选。S6 的非 UI 工作（档案清理、空间治理、迁移和脱敏）及 S7 发布矩阵仍待完成；不能把剩余 MVP 简化为只有 UI。

命令语义参考：[FFmpeg 官方命令文档](https://ffmpeg.org/ffmpeg.html)、[滤镜文档](https://ffmpeg.org/ffmpeg-filters.html)。开发依赖版本沿用已锁定基线，不借本阶段升级依赖。

恢复操作在发布前停止会持久化 CANCELLED 并释放预留，启动不自动发布；发布门开始后完成不可取消的核对。S5 数据根禁止用 S4/更早程序降级打开。

最终 S5 回归 90 项（sharedLogic 54、desktopApp 36），0 失败、0 跳过；自带运行时包的 AAC 任务、持久历史、采样/静态分析及 v2→v3 数据升级冒烟均通过。报告保存在本地 test-output/s5-*。
