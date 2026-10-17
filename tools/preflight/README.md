# 开发前实验运行说明

0.3 最新验收见 [S0 报告](../../docs/planning/06-s0-acceptance-and-cleanup.md)。生成媒体/复制运行时/build 已回收；保留源码、锁、摘要和校验归档。latest 中的历史 work 路径可能不存在，补充媒体实验需要先生成新主实验产物。

## 日常技术回归（无需重新压制全片）

```powershell
.\gradlew.bat -p validation/2026-10-17/build-probe --no-configuration-cache test dropEventAcceptance commitAcceptance createDistributable
python tools/preflight/run_packaged_acceptance.py
python tools/preflight/bridge_readonly.py
python tools/preflight/ntfs_metadata.py
```

dropEventAcceptance 是公开 AWT 合成事件调用真实 Compose 接收器，不是系统鼠标手势测试；test 使用 Compose 内部语义/事件；bridge_readonly 只查询专用 EXE 的 JAB 信息，进程级启用，不执行 UI 动作、不修改全局配置。没有 Robot/SendInput/computer-use。commitAcceptance 对小夹具真实操作并在 8 点终止自己的 JVM/EXE，不操作用户原片。

清理前运行 archive_acceptance.py 校验 ZIP CRC/逐项 SHA256；cleanup_validation.ps1 默认只预览，确认目录与归档正确后用 -Apply 执行明确白名单。不要将这些清理规则用于用户正式任务/唯一备份，它们仅适用于本次开发实验。

这些是独立技术实验，不是正式压制引擎。日期目录保存一次设计核验的证据，运行 ID 隔离每次产物。脚本不运行原 BAT/PS1，不覆盖 `test_resources` 原片；ReplaceFile 实验只针对生成文件或复制的媒体。

## 环境与顺序

Windows x64、Python 3.12+ 标准库、JDK 21；FFmpeg/测试片路径固定在 `preflight.py` 顶部，当前对应用户授权路径。完整运行会创建数 GB 产物并占用 GPU，请预留空间。不要把实验路径改成原始素材替换目标。

从项目根执行（`python` 替换为自己的 Python 路径）：

```powershell
python tools/preflight/preflight.py --full
python tools/preflight/supplemental.py
```

`preflight.py` 不加 `--full` 只做短样片和基础实验。`supplemental.py` 依赖 latest 指向的完整压制产物，检查音轨 copy、MKV 转封装、截断候选拒绝、12s VMAF 及修正后的错误码处理。任一断言失败应返回非零，先读具体 FAIL，不只看 shell 是否结束。

历史首次完整命令有输入端 `-ss 0`，补充检查发现少首个 AAC 包；当前 encode 已改为全片不加 seek。`refinements.py` 是专门重现此缺陷的诊断脚本，**只应对首次 `20261017-163429` 的旧产物运行**；它要求旧产物少首包，再用新命令重编码对照。重新跑过主实验并改变 latest 后，不应把该诊断预期当成当前正常结果。历史日志保留，不能重写成全绿。

## Kotlin/JVM 与打包样机

独立目录 `validation/2026-10-17/build-probe`，使用项目 Gradle Wrapper：

```powershell
.\gradlew.bat -p validation/2026-10-17/build-probe --no-configuration-cache selfTest createDistributable packageExe
python tools/preflight/portable_check.py
```

若 toolchain 自动解析不到已安装 JDK，设置 JAVA_HOME，并通过 `-Dorg.gradle.java.home=绝对路径` 明确选择 JDK 21。样机包含 SQLDelight 生成 SQL/迁移、真实 JVM 进程崩溃回滚、JSON 快照、自定义 JNA ReplaceFileW、DWM、Foundation 帮助与拖放候选实现。

portable_check 把 app-image 复制到中文/空格目录，清空可用外部 Java 路径，从 test_resources 作为 CWD 启动 EXE `--self-test`，检查同级数据与 native DLL；退出码和结果在 reports/portable-check.json。它不能替代干净 VM/实际安装验收。

打包任务可能清理 app-image；先关闭本次样机并归档有用日志。样机的同级数据仅作实验，不是用户正式档案，不能将构建目录当生产持久目录。正式升级必须保留 ErgouCompressData。

## 证据与手工项目

- `validation/2026-10-17/logs/<runId>`：每条 argv、stdout/stderr、退出码和耗时。
- `reports/<runId>/preflight-results.json`：逐项 Pass/Fail，源身份、媒体探测、进度与估算。
- `reports/latest.json`、`supplemental-latest.json`、`refinements-latest.json`：各实验指针，不能互相混淆。
- Gradle 日志、依赖锁和 SHA256 verification：实际构建证据，不是安全认证。

真实资源管理器拖放仍无释放成功证据；0.3 的 10 项事件测试已覆盖 Copy 协商与路径安全，在 EXE 上重复通过。系统手势和光标仍是实际产品交互验收，不能将该事件测试改写为 Explorer 端到端通过。正式交付须自然导入，并提供“添加文件/文件夹”入口。

JAB 只读链路和 4 项语义/焦点事件已测；实际读屏、混合 DPI/IME、干净机安装升级、正式业务引擎回归和更多媒体/设备在最新 S0 报告分别列出，不标为已测。
