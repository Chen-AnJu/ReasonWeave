[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$secretDirectory = Join-Path $repositoryRoot '.local\secrets'
$passwordPath = Join-Path $secretDirectory 'postgres_password'

New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
if (Test-Path -LiteralPath $passwordPath) {
    Write-Output 'Local configuration already exists; no secret was overwritten.'
    exit 0
}

$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$password = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$encoding = [Text.UTF8Encoding]::new($false)
$stream = [IO.File]::Open($passwordPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $writer = [IO.StreamWriter]::new($stream, $encoding)
    try { $writer.WriteLine($password) } finally { $writer.Dispose() }
} finally {
    $stream.Dispose()
}

Write-Output 'Created .local/secrets/postgres_password'
