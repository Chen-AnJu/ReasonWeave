[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$repositoryPrefix = $repositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$relativeTargets = @(
    '.artifacts',
    'backend/target',
    'frontend/dist',
    'frontend/test-results',
    'frontend/playwright-report',
    'frontend/output/playwright'
)

$removedDirectories = 0
$removedBytes = [long]0
foreach ($relativeTarget in $relativeTargets) {
    $target = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $relativeTarget))
    if ($target -eq $repositoryRoot -or
        -not $target.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup target outside the repository: $target"
    }
    if (-not (Test-Path -LiteralPath $target)) {
        continue
    }

    $item = Get-Item -LiteralPath $target -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing cleanup target that is a reparse point: $target"
    }
    $bytes = (Get-ChildItem -LiteralPath $target -File -Recurse -Force -ErrorAction Stop |
        Measure-Object -Property Length -Sum).Sum
    Remove-Item -LiteralPath $target -Recurse -Force
    $removedDirectories += 1
    if ($null -ne $bytes) {
        $removedBytes += [long]$bytes
    }
}

Write-Host "Generated artifact cleanup removed $removedDirectories directorie(s), $removedBytes byte(s)."
