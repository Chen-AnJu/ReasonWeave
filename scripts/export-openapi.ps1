param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1024, 65535)]
    [int]$Port = 18081,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$jarPath = Join-Path $backendRoot 'target\reasonweave-backend-0.4.1.jar'
$artifactRoot = Join-Path $repositoryRoot '.artifacts'
$generatedContract = Join-Path $artifactRoot 'reasonweave-v1.generated.json'
$standardOutput = Join-Path $artifactRoot 'openapi-export.stdout.log'
$standardError = Join-Path $artifactRoot 'openapi-export.stderr.log'
$contractDirectory = Join-Path $repositoryRoot 'contracts\openapi'
$contractPath = Join-Path $contractDirectory 'reasonweave-v1.json'
$domainPackRoot = (Resolve-Path (Join-Path $repositoryRoot 'domain-packs')).Path

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'test-backend.ps1') `
        -JavaHome $JavaHome `
        -MavenArguments @('-DskipTests', 'package')
}

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Backend executable JAR is missing: $jarPath"
}

if ($JavaHome) {
    $resolvedJavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
    $javaExecutable = Join-Path $resolvedJavaHome 'bin\java.exe'
}
else {
    $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
}
$versionOutput = (& $javaExecutable -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch 'version "21(?:\.|\")') {
    throw "Java 21 is required to export OpenAPI.`n$versionOutput"
}

New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
New-Item -ItemType Directory -Path $contractDirectory -Force | Out-Null

$arguments = @(
    '-jar', $jarPath,
    '--spring.profiles.active=openapi-export',
    "--server.port=$Port",
    '--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/reasonweave',
    "--rw.domain-pack-roots=$domainPackRoot"
)
$process = Start-Process `
    -FilePath $javaExecutable `
    -ArgumentList $arguments `
    -WorkingDirectory $backendRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $standardOutput `
    -RedirectStandardError $standardError `
    -PassThru

try {
    $schemaUrl = "http://127.0.0.1:$Port/api/v1/openapi"
    $exported = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($process.HasExited) {
            $stderr = if (Test-Path -LiteralPath $standardError) { Get-Content -LiteralPath $standardError -Raw } else { '' }
            throw "OpenAPI export process exited before the endpoint was ready.`n$stderr"
        }
        try {
            Invoke-WebRequest -Uri $schemaUrl -OutFile $generatedContract -UseBasicParsing
            $exported = $true
            break
        }
        catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $exported) {
        throw "OpenAPI endpoint was not ready within 30 seconds: $schemaUrl"
    }

    $raw = Get-Content -LiteralPath $generatedContract -Raw
    $document = $raw | ConvertFrom-Json -AsHashtable
    if ($document.openapi -notmatch '^3\.') {
        throw 'Generated document is not an OpenAPI 3 contract.'
    }
    if (-not $document.paths.ContainsKey('/api/v1/runtime')) {
        throw 'Generated contract is missing /api/v1/runtime.'
    }
    if ($document.paths.ContainsKey('/api/v1/session') -or $raw.Contains('workspace_id')) {
        throw 'Generated contract exposes a removed session or workspace API field.'
    }

    # Springdoc derives this value from the temporary export listener. The
    # fixed public contract is same-origin and must not retain that host/port.
    $document.Remove('servers')
    $normalized = $document | ConvertTo-Json -Depth 100 -Compress
    [IO.File]::WriteAllText(
        $generatedContract,
        $normalized,
        [Text.UTF8Encoding]::new($false)
    )

    Copy-Item -LiteralPath $generatedContract -Destination $contractPath -Force
}
finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        $process.WaitForExit(5000) | Out-Null
    }
}

Push-Location $repositoryRoot
try {
    & pnpm --dir frontend api:types
    if ($LASTEXITCODE -ne 0) {
        throw "TypeScript contract generation failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

[pscustomobject]@{
    contract = $contractPath
    paths = $document.paths.Count
    schemas = $document.components.schemas.Count
    sha256 = (Get-FileHash -LiteralPath $contractPath -Algorithm SHA256).Hash
}
