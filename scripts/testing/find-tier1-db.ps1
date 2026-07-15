$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..\..\peegeeq-db\src\test\java'
$files = Get-ChildItem -Path $root -Recurse -Filter *.java
$results = @()
$pattern = '\.onSuccess\(\s*\w+\s*->\s*\w+\.verify\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\)\s*\)\s*\.onFailure\(\s*\w+::failNow\s*\)'
foreach ($f in $files) {
    $content = Get-Content $f.FullName -Raw
    $patternMatches = [regex]::Matches($content, $pattern)
    foreach ($m in $patternMatches) {
        $lineNum = ($content.Substring(0, $m.Index) -split "`n").Count
        $results += [PSCustomObject]@{
            File = $f.FullName.Replace((Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path + '\','')
            Line = $lineNum
        }
    }
}
$results | ForEach-Object { "{0}:{1}" -f $_.File, $_.Line }
"TOTAL: $($results.Count)"
