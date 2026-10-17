param(
    [string]$Name = 's0-s2',
    [int]$ExpectedTests = 0
)

$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Name must be a simple report prefix' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputRoot = Join-Path $projectRoot 'test-output'
$evidenceRoot = Join-Path $outputRoot "$Name-junit"
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$resultRoots = @('sharedLogic/build/test-results/jvmTest', 'sharedUI/build/test-results/jvmTest', 'desktopApp/build/test-results/test')
$suites = @(foreach ($relativeRoot in $resultRoots) {
    $moduleName = $relativeRoot.Split('/')[0]
    Get-ChildItem -LiteralPath (Join-Path $projectRoot $relativeRoot) -Filter 'TEST-*.xml' | ForEach-Object {
        $suiteXml = [xml](Get-Content -LiteralPath $_.FullName -Raw)
        $suite = $suiteXml.testsuite
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $evidenceRoot ($moduleName + '-' + $_.Name))
        [PSCustomObject][ordered]@{
            module = $moduleName; suite = $suite.name; tests = [int]$suite.tests
            failures = [int]$suite.failures + [int]$suite.errors; skipped = [int]$suite.skipped
            timeSeconds = [double]::Parse($suite.time, [Globalization.CultureInfo]::InvariantCulture)
        }
    }
})
if ($suites.Count -eq 0) { throw 'No JUnit XML found; run Gradle tests first' }
$summary = [ordered]@{
    recordedAt = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time').ToString('o')
    timezone = 'Asia/Shanghai'
    totalTests = [int]($suites | Measure-Object -Property tests -Sum).Sum
    failures = [int]($suites | Measure-Object -Property failures -Sum).Sum
    skipped = [int]($suites | Measure-Object -Property skipped -Sum).Sum
    suites = $suites
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot "$Name-test-summary.json") -Encoding utf8
[PSCustomObject]$summary | Select-Object totalTests, failures, skipped | Format-Table
if ($summary.failures -ne 0) { throw 'JUnit reported failures' }
if ($ExpectedTests -gt 0 -and $summary.totalTests -ne $ExpectedTests) { throw "Expected $ExpectedTests tests, found $($summary.totalTests)" }
