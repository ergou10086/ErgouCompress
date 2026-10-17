# ErgouCompress

Windows 优先的 Kotlin Multiplatform / Compose Desktop 媒体工具。已进入 S1–S2：工具真实自检、目录扫描与多选、参数与单项覆盖、冻结计划、逐项编码副本、完整校验和不覆盖发布。当前提供保守 SDR 路径；持久队列/恢复在 S3，带备份原地替换在 S4。

## 运行与构建

需要 JDK 21，使用仓库 Gradle Wrapper。开发启动的数据根是项目下 `ErgouCompressData`；便携运行的数据根是实际 EXE 同级目录。

```powershell
.\gradlew.bat :desktopApp:run
.\gradlew.bat :sharedLogic:jvmTest :sharedUI:jvmTest :desktopApp:test
.\gradlew.bat :desktopApp:createDistributable --no-configuration-cache -x downloadWix
```

最后一条只生成便携 app-image，不需要安装器的 WiX 下载任务。运行入口为 `desktopApp/build/compose/binaries/main/app/ErgouCompress/ErgouCompress.exe`；分发时须带上整个目录中的 `app` 和 `runtime`。可选安装器使用 `:desktopApp:packageExe --no-configuration-cache`，需要可用 WiX。Compose 1.12.1 的 WiX 下载任务存在配置缓存兼容问题，打包暂时关闭配置缓存。

使用顺序：选择 FFmpeg（ffprobe 可留空自动查找同目录）→ 验证工具 → 添加输入 → 选择独立输出目录 → 扫描 → 审核选择和参数 → 冻结 → 在目标审核卡片逐项处理。所有可见设置都有悬停、焦点与点击帮助。只有通过真实自检的 NVIDIA 才可选；不会静默改用 CPU。

当前支持一个真实主视频、已确认的 8bit SDR、受支持音轨全部复制，保持分辨率/帧率，MP4/MKV 输出。HDR、旋转、交错、多视频轨、字幕/附件/数据流进入审核而不丢弃。副本允许比源大；CRF/CQ 不保证输出大小。平铺输出遇到同名项时阻止冻结，需要分开任务或选择其他目录。

立即停止会发送 q，超时终止进程树；即便返回 0 也不会发布该候选。目标卷复制准备可逐块停止；最终发布及其证据核对不可取消，完成后再退出。当前文件下次重跑。失败候选、日志和校验凭据留在数据根；当前没有自动清理或跨重启恢复功能。副本发布需支持硬链接的本地文件系统，其他目标明确失败并保留候选。

## 测试

普通测试不依赖 FFmpeg 或 GPU。显式媒体测试只生成小型合成夹具，不读取私有样片：

```powershell
$env:ERGOU_TEST_FFMPEG = '你自己的绝对路径/ffmpeg.exe'
$env:ERGOU_TEST_FFPROBE = '你自己的绝对路径/ffprobe.exe'
.\gradlew.bat :sharedLogic:jvmTest :sharedUI:jvmTest :desktopApp:test -PmediaTests=true
```

没有可用 GPU 时 NVENC 测试记为 skipped。路径未设置时显式媒体测试失败，不冒充通过。JUnit XML/HTML 分别位于各模块的 `build/test-results` 和 `build/reports/tests`。`ErgouCompress.exe --diagnostics` 执行真实目录/SQLite/native 启动探针，写入数据根 `diagnostics/startup.json`；支持 `--data-root=绝对路径` 与 `--strict-portable`。

测试后可运行 `./tools/development/collect_test_results.ps1` 汇总 JUnit 并复制本地证据；`-ExpectedTests` 可约束某次验收的用例数。文档检查使用 `python tools/preflight/check_documents.py --report test-output/document-check.json`，避免覆盖历史 S0 报告。

## 代码边界

- `sharedLogic/commonMain/domain`：版本化媒体/工具/计划、保守兼容策略、结构化错误。
- `sharedLogic/commonMain/application`：限流探测、冻结计划、单文件编码/校验/发布编排。
- `sharedLogic/commonMain/ports`：文件、进程、探测、编码、校验、档案与 UI facade 契约。
- `sharedLogic/jvmMain/infrastructure`：NIO、FFmpeg argv/JSON/progress、并行管道、校验和副本发布。
- `sharedUI/commonMain`：只读状态、控制器、Foundation 页面与统一设置帮助；`jvmMain` 隔离拖放和选择器。
- `desktopApp/bootstrap`：数据根探针、单实例、工具路径设置和显式依赖装配；`platform/windows`：创建时 Job Object、Unicode file ID。

接口采用端口/适配器，编码器策略和独立验证器可替换；依赖由 composition root 显式注入，不使用全局 service locator。测试通过公共契约注入可控 fake，并使用真实 NIO/Win32/子进程/媒体集成覆盖边界。Android/Web 模板停用，不参与本版构建或交付。

本地设计与阶段验收在 `docs`，开发前实验脚本在 `tools/preflight`，独立技术样机源码在 `validation/2026-10-17/build-probe`。按项目要求，`docs/`、私有 `test_resources/`、验收报告/日志、生成媒体、数据根、构建输出与本机配置被 Git 忽略；`src/*Test` 和可复跑的技术样机源码保留版本管理。依赖锁和校验清单纳入 Git；新增 SHA256 是依赖固定记录，不是第三方认证。
