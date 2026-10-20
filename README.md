# ErgouCompress
基于 FFmpeg 的本地批量媒体压缩工具，使用 Kotlin 共享用例和 Compose 桌面界面。

桌面工作台采用 Windows 11 Fluent 风格：无侧栏主页、文件与文件夹拖入自动扫描、多选审核列表、实时任务卡、独立任务设置与日志窗口，以及顶部设置、历史、关于。扫描完成后主页可直接开始处理；“处理选项”保留为完整审核、估算与逐轨策略入口。运行任务时可以继续导入并创建下一项任务。

```powershell
.\gradlew.bat :desktopApp:run
```

完整启动、测试、打包、依赖锁与 IDEA Gradle 排障命令见 [开发与启动命令](docs/development-commands.md)；本次 UI/UX 设计说明见 [桌面 UI/UX 重构](docs/planning/12-desktop-ui-redesign.md)。

首次使用，在设置中选择自己的 `ffmpeg.exe` 和 `ffprobe.exe`，完成实际编码自检后导入素材。输出目录须已存在；同名目标会报告冲突。默认保存副本、复制音轨、保留分辨率与帧率，仅在结果更小时发布。固定质量大小需要可选采样估算，估算不保证最终体积。

支持 CPU H.264、NVIDIA HEVC、AAC、MP4 / MKV、逐轨 Copy / Encode / Drop、完整解码校验，以及带备份的安全原地提交。原地处理前需在设置中登记源文件卷的独立工作区。当前自动处理路径针对已验证的 8-bit 4:2:0 SDR 视频；HDR、复杂侧数据与其他媒体组合会提示审核。

任务参数、执行尝试和事件存入本地档案，历史支持继续、重试、目标位置与备份恢复；设置包含外观、默认参数、空间统计、保留策略、清理预览、诊断导出与数据根迁移。偏好只影响新草稿，已经冻结的任务保留原参数。详细日志可能按保留策略清理，窗口显示行数有上限。

开发启动的数据根默认位于项目中的 `ErgouCompressData`，打包启动默认位于程序旁；可用 `--data-root=绝对路径` 指定。`--open=绝对路径` 可重复传入初始素材。迁移后需重启，处理中关闭会先安全停止。若 IDEA 报告 `DependencyVerificationException` 或依赖锁不在状态中，按命令文档第 5 节重新生成锁与校验元数据。

```powershell
# 普通契约与桌面交互测试
.\gradlew.bat :sharedLogic:jvmTest :sharedUI:jvmTest :desktopApp:test
# 本地统一回归入口；-Media 增加真实 FFmpeg 测试并在结束后清理输出
& sharedLogic/src/commonTest/resources/development-scripts/verification/verify-backend.ps1 -Media
```

开发脚本集中在 `sharedLogic/src/commonTest/resources/development-scripts`，按项目约定仅在本地保留并忽略；全新克隆可直接执行 Gradle 测试。生产代码、Kotlin 测试和依赖锁正常跟踪。应用采用 GPL-3.0，FFmpeg 由用户自行提供。
