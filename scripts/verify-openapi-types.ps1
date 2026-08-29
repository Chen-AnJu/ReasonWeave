param(
    [string]$BaseUrl = ''
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$contractPath = Join-Path $repositoryRoot 'contracts\openapi\reasonweave-v1.json'
$expectedTypesPath = Join-Path $repositoryRoot 'frontend\src\api\schema.d.ts'
$artifactDirectory = Join-Path $repositoryRoot '.artifacts'
$generatedTypesPath = Join-Path $artifactDirectory 'openapi-schema.generated.d.ts'
$liveContractPath = Join-Path $artifactDirectory 'openapi-live.json'

foreach ($requiredPath in @($contractPath, $expectedTypesPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required API contract is missing: $requiredPath"
    }
}

New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

if (-not [string]::IsNullOrWhiteSpace($BaseUrl)) {
    $schemaUrl = "$($BaseUrl.TrimEnd('/'))/api/v1/openapi"
    Invoke-WebRequest -Uri $schemaUrl -OutFile $liveContractPath -UseBasicParsing

    # Springdoc derives this entry from the listener serving the document. The
    # fixed public contract is same-origin, so listener-specific hosts and ports
    # are intentionally excluded from drift detection.
    $liveDocument = Get-Content -LiteralPath $liveContractPath -Raw | ConvertFrom-Json -AsHashtable
    $liveDocument.Remove('servers')
    [IO.File]::WriteAllText(
        $liveContractPath,
        ($liveDocument | ConvertTo-Json -Depth 100 -Compress),
        [Text.UTF8Encoding]::new($false)
    )

    & node (Join-Path $PSScriptRoot 'compare-json.mjs') $contractPath $liveContractPath
    if ($LASTEXITCODE -ne 0) {
        throw "Backend OpenAPI has drifted from $contractPath"
    }
}

Push-Location $repositoryRoot
try {
    & pnpm --dir frontend exec openapi-typescript ../contracts/openapi/reasonweave-v1.json -o ../.artifacts/openapi-schema.generated.d.ts
    if ($LASTEXITCODE -ne 0) {
        throw "OpenAPI type generation failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$expectedHash = (Get-FileHash -LiteralPath $expectedTypesPath -Algorithm SHA256).Hash
$generatedHash = (Get-FileHash -LiteralPath $generatedTypesPath -Algorithm SHA256).Hash
if ($expectedHash -ne $generatedHash) {
    throw "Generated TypeScript API types have drifted. Regenerate from contracts/openapi/reasonweave-v1.json."
}

[pscustomobject]@{
    fixed_contract = $contractPath
    generated_types = $expectedTypesPath
    sha256 = $expectedHash
    live_checked = -not [string]::IsNullOrWhiteSpace($BaseUrl)
    status = 'MATCHED'
}
