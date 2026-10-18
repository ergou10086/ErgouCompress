<#
.SYNOPSIS
运行后端与可选原型回归，记录简要结论并清理本轮临时产物。
.DESCRIPTION
Media 启用真实 FFmpeg 媒体测试；Prototype 使用临时 jsdom 依赖检查交互，不启动浏览器。
#>
param([switch]$Media, [switch]$Prototype)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runDirectory = Join-Path $projectRoot ('test-output/verification-' + [Guid]::NewGuid())
$systemTemporary = Join-Path ([IO.Path]::GetTempPath()) ('ergou-verification-' + [Guid]::NewGuid())
$reports = @('sharedLogic/build/test-results/jvmTest', 'desktopApp/build/test-results/test')
$cleanTargets = $reports + @(
    'sharedLogic/build/reports/tests/jvmTest', 'desktopApp/build/reports/tests/test',
    'sharedLogic/build/classes/kotlin/jvm/test', 'desktopApp/build/classes/kotlin/test',
    'sharedLogic/build/kotlin/compileTestKotlinJvm', 'desktopApp/build/kotlin/compileTestKotlin',
    'sharedLogic/build/tmp/jvmTest', 'desktopApp/build/tmp/test'
)
$oldJsdom = $env:ERGOU_JSDOM
$oldFfmpeg = $env:ERGOU_TEST_FFMPEG
$oldFfprobe = $env:ERGOU_TEST_FFPROBE

function Remove-OwnedOutput([string]$candidate) {
    $resolved = [IO.Path]::GetFullPath($candidate)
    $inProject = $resolved.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
    $inOwnedTemporary = $resolved.Equals([IO.Path]::GetFullPath($systemTemporary), [StringComparison]::OrdinalIgnoreCase)
    if (!$inProject -and !$inOwnedTemporary) {
        throw "测试清理目标越界：$resolved"
    }
    if (!(Test-Path -LiteralPath $resolved)) { return }
    $ancestor = [IO.Directory]::GetParent($resolved)
    while ($ancestor) {
        if ((Get-Item -LiteralPath $ancestor.FullName -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "测试输出祖先是重解析点：$($ancestor.FullName)" }
        $ancestor = $ancestor.Parent
    }
    # 先检查目录和全部子项，禁止递归删除穿过重解析点。
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($resolved)
    while ($pending.Count) {
        $entry = Get-Item -LiteralPath $pending.Pop() -Force
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "测试清理遇到重解析点：$($entry.FullName)" }
        if ($entry.PSIsContainer) { Get-ChildItem -LiteralPath $entry.FullName -Force | ForEach-Object { $pending.Push($_.FullName) } }
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
    New-Item -ItemType Directory -Path $systemTemporary | Out-Null
    $arguments = @(':sharedLogic:jvmTest', ':desktopApp:test', '--offline', '--no-configuration-cache', '--rerun-tasks')
    $arguments += "-PergouTestTemp=$systemTemporary"
    if ($Media) {
        if (!$env:ERGOU_TEST_FFMPEG) { $env:ERGOU_TEST_FFMPEG = (Get-Command ffmpeg -ErrorAction Stop).Source }
        if (!$env:ERGOU_TEST_FFPROBE) { $env:ERGOU_TEST_FFPROBE = (Get-Command ffprobe -ErrorAction Stop).Source }
        $arguments += '-PmediaTests=true'
    }
    & .\gradlew.bat @arguments
    $backendExit = $LASTEXITCODE
    $summary = [Collections.Generic.List[string]]::new()
    $summary.Add('# 最近一次后端回归')
    $summary.Add('')
    $summary.Add("真实媒体：$Media；Gradle 退出码：$backendExit。原始报告在记录结论后清理。")
    $summary.Add('')
    $summary.Add('| 套件 | 用例 | 失败 | 错误 | 跳过 |')
    $summary.Add('| --- | --- | --- | --- | --- |')
    foreach ($relative in $reports) {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $relative) -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            [xml]$result = Get-Content -LiteralPath $_.FullName -Raw
            $suite = $result.testsuite
            $summary.Add("| $($suite.name) | $($suite.tests) | $($suite.failures) | $($suite.errors) | $($suite.skipped) |")
            foreach ($case in $suite.testcase) {
                if ($case.failure) { $summary.Add("失败：$($case.name)；$($case.failure.message)") }
            }
        }
    }
    if ($Prototype -and $backendExit -eq 0) {
        & npm install --prefix $runDirectory --cache (Join-Path $runDirectory 'npm-cache') --no-save --package-lock=false --ignore-scripts --no-audit --no-fund jsdom@26.1.0
        if ($LASTEXITCODE -ne 0) { throw '原型检查依赖安装失败' }
        $env:ERGOU_JSDOM = Join-Path $runDirectory 'node_modules/jsdom'
        & node --test design/desktop-prototype/tests/prototype.test.cjs
        $prototypeExit = $LASTEXITCODE
        $summary.Add('')
        $summary.Add("原型 DOM 交互：6 项；退出码：$prototypeExit。视觉、DPI 与读屏需实际浏览器/桌面另行验收。")
    } else { $prototypeExit = 0 }
    $reportDirectory = Join-Path $projectRoot 'docs/report'
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    $summary | Set-Content -LiteralPath (Join-Path $reportDirectory 'latest-backend-verification.md') -Encoding utf8
    if ($backendExit -ne 0 -or $prototypeExit -ne 0) { throw '回归未通过，简要失败结论已写入 docs/report。' }
} finally {
    # 不删除测试源码、代码夹具、用户原片或既有业务档案。
    foreach ($relative in $cleanTargets) { Remove-OwnedOutput (Join-Path $projectRoot $relative) }
    Remove-OwnedOutput $runDirectory
    # 工作 JVM 退出后再清理原生库解压目录，避免 Windows DLL 锁造成 JUnit 目录残留。
    Remove-OwnedOutput $systemTemporary
    $env:ERGOU_JSDOM = $oldJsdom
    $env:ERGOU_TEST_FFMPEG = $oldFfmpeg
    $env:ERGOU_TEST_FFPROBE = $oldFfprobe
    Pop-Location
}
