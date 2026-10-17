param([switch]$Apply)
$ErrorActionPreference='Stop'
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$reportRoot=Join-Path $repositoryRoot 'validation/2026-10-17/reports'
$manifest=Get-Content -LiteralPath (Join-Path $reportRoot 'evidence-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.status -ne 'VERIFIED') { throw 'Archive not verified' }
if ((Get-FileHash -LiteralPath (Join-Path $repositoryRoot $manifest.archive) -Algorithm SHA256).Hash -ne $manifest.archiveSha256) { throw 'Archive changed' }
$tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$rows=[Collections.Generic.List[object]]::new()
foreach($temporaryRoot in $manifest.temporaryRoots) {
    $target=[IO.Path]::GetFullPath($temporaryRoot)
    if((Split-Path $target -Parent) -ne $tempParent -or (Split-Path $target -Leaf) -notlike 'ergou-preflight-cross-volume-*') { throw 'Unsafe temp root' }
    try {
        $item=Get-Item -LiteralPath $target -Force
        $children=@(Get-ChildItem -LiteralPath $target -Force)
        if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $children.Count -ne 1 -or $children[0].Name -ne 'candidate.bin' -or $children[0].PSIsContainer -or ($children[0].Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected fixture contents' }
        if([IO.File]::ReadAllText($children[0].FullName) -ne 'cross-volume') { throw 'Fixture content changed' }
        $bytes=[long]$children[0].Length
        if($Apply){Remove-Item -LiteralPath $target -Recurse -Force}
        $rows.Add([pscustomobject]@{path=$target;bytes=$bytes;kind='registered-cross-volume-temp-owned-context';removed=[bool]$Apply})
    } catch { $rows.Add([pscustomobject]@{path=$target;bytes=0;removed=$false;reason=$_.Exception.Message}) }
}
$rows.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportRoot 'cleanup-temp-owned.json') -Encoding UTF8
$rows.ToArray() | Select-Object path,removed,bytes | ConvertTo-Json
