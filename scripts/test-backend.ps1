param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$MavenArguments = @('-B', 'verify')
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $workspaceRoot 'backend\mvnw.cmd'
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path

try {
    if ($JavaHome) {
        $resolvedJavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
        $javaExecutable = Join-Path $resolvedJavaHome 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            throw "Java executable not found under JavaHome: $resolvedJavaHome"
        }
        $env:JAVA_HOME = $resolvedJavaHome
        $env:Path = "$(Join-Path $resolvedJavaHome 'bin');$originalPath"
    }
    else {
        $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $javaCommand) {
            throw 'Java 21 is required. Set JAVA_HOME or pass -JavaHome before running backend tests.'
        }
        $javaExecutable = $javaCommand.Source
    }

    $versionOutput = (& $javaExecutable -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch 'version "21(?:\.|\")') {
        throw "Java 21 is required, but the selected runtime is not Java 21.`n$versionOutput"
    }

    Push-Location (Split-Path -Parent $wrapper)
    try {
        & $wrapper @MavenArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Backend Maven verification failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}
