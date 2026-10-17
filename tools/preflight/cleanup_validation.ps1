param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\')
$rootPrefix = $repositoryRoot + '\'
$reportDirectory = Join-Path $repositoryRoot 'validation/2026-10-17/reports'
$manifest = Get-Content -LiteralPath (Join-Path $reportDirectory 'evidence-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.status -ne 'VERIFIED') { throw 'Evidence archive is not verified' }
$archivePath = Join-Path $repositoryRoot $manifest.archive
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $manifest.archiveSha256) { throw 'Archive changed' }
$allowedRoots = @('validation/2026-10-17/work','validation/2026-10-17/build-probe/build',
 'validation/2026-10-17/build-probe/portable-dev','validation/2026-10-17/build-probe/.gradle',
 'desktopApp/build','sharedLogic/build','sharedUI/build','tools/preflight/__pycache__')
$cleanupRows = [Collections.Generic.List[object]]::new()
$ownedTempReport=Join-Path $reportDirectory 'cleanup-temp-owned.json'
if(Test-Path -LiteralPath $ownedTempReport) {
    $ownedRows=Get-Content -LiteralPath $ownedTempReport -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach($row in $ownedRows){if($row.removed){$cleanupRows.Add($row)}}
}
$freeBefore = ([IO.DriveInfo]::new('D')).AvailableFreeSpace
foreach ($relativeRoot in $manifest.cleanupRoots) {
    if ($relativeRoot -notin $allowedRoots) { throw "Unexpected cleanup root $relativeRoot" }
    $target = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $relativeRoot))
    if (-not $target.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw "Outside workspace $target" }
    if (-not (Test-Path -LiteralPath $target)) { continue }
    $item = Get-Item -LiteralPath $target -Force
    if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Unsafe directory $target" }
    $children = @(Get-ChildItem -LiteralPath $target -Recurse -Force)
    if ($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw "Reparse point under $target" }
    $bytes = ($children | Where-Object { -not $_.PSIsContainer } | Measure-Object Length -Sum).Sum
    if ($Apply) { Remove-Item -LiteralPath $target -Recurse -Force }
    $cleanupRows.Add([pscustomobject]@{path=$relativeRoot;bytes=[long]$bytes;kind='generated-directory';removed=[bool]$Apply})
}
foreach ($entry in $manifest.largeEvidenceToRemove) {
    $target = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $entry.path))
    $evidencePrefix = Join-Path $repositoryRoot 'validation/2026-10-17'
    if (-not $target.StartsWith($evidencePrefix+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence outside validation' }
    if (-not (Test-Path -LiteralPath $target)) { continue }
    $item = Get-Item -LiteralPath $target -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe evidence entry' }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Changed evidence $target" }
    if ($Apply) { Remove-Item -LiteralPath $target -Force }
    $cleanupRows.Add([pscustomobject]@{path=$entry.path;bytes=[long]$entry.bytes;kind='verified-archived-large-evidence';removed=[bool]$Apply})
}
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
foreach ($temporaryRoot in $manifest.temporaryRoots) {
    $target = [IO.Path]::GetFullPath($temporaryRoot)
    if ((Split-Path $target -Parent) -ne $tempParent -or (Split-Path $target -Leaf) -notlike 'ergou-preflight-cross-volume-*') { throw "Unsafe temp path $target" }
    if (-not (Test-Path -LiteralPath $target)) { continue }
    $item=Get-Item -LiteralPath $target -Force
    $children=@(Get-ChildItem -LiteralPath $target -Force)
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $children.Count -ne 1 -or $children[0].Name -ne 'candidate.bin' -or $children[0].PSIsContainer -or ($children[0].Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Temp contents changed $target" }
    if ([IO.File]::ReadAllText($children[0].FullName) -ne 'cross-volume') { throw "Temp payload changed $target" }
    $bytes=[long]$children[0].Length
    if ($Apply) { Remove-Item -LiteralPath $target -Recurse -Force }
    $cleanupRows.Add([pscustomobject]@{path=$target;bytes=$bytes;kind='registered-cross-volume-temp';removed=[bool]$Apply})
}
$source = Join-Path $repositoryRoot 'test_resources/[餅月なこ][2026-05-13][_YouTube非公開動画.mp4'
$sourceItem = Get-Item -LiteralPath $source
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceMtimeNs = [long](($sourceItem.LastWriteTimeUtc.Ticks - [DateTime]::UnixEpoch.Ticks) * 100)
if ($sourceHash -ne $manifest.source.sha256 -or $sourceItem.Length -ne $manifest.source.bytes -or $sourceMtimeNs -ne $manifest.source.mtimeNs) { throw 'Original source changed' }
$freeAfter = ([IO.DriveInfo]::new('D')).AvailableFreeSpace
$deletedBytes = [long](($cleanupRows | Where-Object removed | Measure-Object bytes -Sum).Sum)
$report = [ordered]@{status=$(if($Apply){'COMPLETE'}else{'DRY_RUN'});capturedAt=(Get-Date).ToString('o');
 deletedFileBytes=$deletedBytes;archiveBytes=[long]$manifest.archiveBytes;netLogicalReclaimedBytes=($deletedBytes-[long]$manifest.archiveBytes);
 driveFreeBefore=$freeBefore;driveFreeAfter=$freeAfter;observedFreeDelta=($freeAfter-$freeBefore);
 source=[ordered]@{status='UNCHANGED';bytes=$sourceItem.Length;sha256=$sourceHash;mtimeNs=$sourceMtimeNs};
 preserved=@('test_resources original','docs','tools and sample sources','SQL/locks/verification','scaffold-before','readable summaries','verified evidence ZIP','global Gradle/JDK/FFmpeg');
 entries=$cleanupRows.ToArray()}
$name=if($Apply){'cleanup-report.json'}else{'cleanup-plan.json'}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory $name) -Encoding UTF8
[pscustomobject]$report | Select-Object status,deletedFileBytes,archiveBytes,netLogicalReclaimedBytes,observedFreeDelta | ConvertTo-Json
