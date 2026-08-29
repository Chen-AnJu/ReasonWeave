[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot '.artifacts'))
$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    [IO.Path]::GetFullPath((Join-Path $artifactRoot 'cli-release'))
} else {
    [IO.Path]::GetFullPath($OutputDirectory)
}
$workspacePrefix = $workspaceRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$artifactPrefix = $artifactRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar

if (-not $outputRoot.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "CLI output must be inside $artifactRoot"
}
if (-not $outputRoot.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Resolved CLI output escaped the repository.'
}

if (Test-Path -LiteralPath $outputRoot) {
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$packages = @(
    @{ Directory = 'tools/domain-pack-cli'; RequiredSource = 'package/src/cli.mjs' },
    @{ Directory = 'tools/evidence-cli'; RequiredSource = 'package/src/cli.mjs' }
)

foreach ($package in $packages) {
    $packageDirectory = Join-Path $workspaceRoot $package.Directory
    & pnpm --dir $packageDirectory pack --pack-destination $outputRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to package $($package.Directory)."
    }
}

$archives = @(Get-ChildItem -LiteralPath $outputRoot -Filter '*.tgz' -File | Sort-Object Name)
if ($archives.Count -ne 2) {
    throw "Expected exactly two CLI archives, found $($archives.Count)."
}

$requiredCommon = @('package/package.json', 'package/README.md', 'package/LICENSE')
foreach ($archive in $archives) {
    $entries = @(& tar -tzf $archive.FullName)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect $($archive.Name)." }
    foreach ($required in $requiredCommon) {
        if ($required -notin $entries) { throw "$($archive.Name) is missing $required." }
    }
    if ('package/src/cli.mjs' -notin $entries) {
        throw "$($archive.Name) is missing package/src/cli.mjs."
    }
    if ($archive.Name -like '*domain-pack-cli*' -and
        'package/src/manifest.schema.json' -notin $entries) {
        throw "$($archive.Name) is missing package/src/manifest.schema.json."
    }
    $forbidden = @($entries | Where-Object {
        $_ -match '(^|/)node_modules/' -or $_ -match '^package/test/' -or $_ -match '^package/\.artifacts/'
    })
    if ($forbidden.Count -gt 0) {
        throw "$($archive.Name) contains forbidden entries: $($forbidden -join ', ')"
    }
}

$checksumLines = foreach ($archive in $archives) {
    $hash = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($archive.Name)"
}
$checksumPath = Join-Path $outputRoot 'SHA256SUMS'
[IO.File]::WriteAllLines($checksumPath, $checksumLines, [Text.UTF8Encoding]::new($false))

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$smokeRoot = [IO.Path]::GetFullPath((Join-Path $temporaryRoot "reasonweave-cli-smoke-$PID-$([Guid]::NewGuid().ToString('N'))"))
if (-not $smokeRoot.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Resolved CLI smoke directory escaped the system temporary directory.'
}
$pnpmStore = (& pnpm store path | Select-Object -Last 1).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pnpmStore)) {
    throw 'Unable to resolve the pnpm content-addressable store.'
}
try {
    New-Item -ItemType Directory -Path $smokeRoot -Force | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $smokeRoot 'package.json'),
        "{`n  `"private`": true`n}`n",
        [Text.UTF8Encoding]::new($false)
    )
    & pnpm --dir $smokeRoot --store-dir $pnpmStore add --offline --ignore-scripts @($archives.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to install the CLI archives for a smoke test.' }

    $binSuffix = if ($IsWindows) { '.cmd' } else { '' }
    $helpPatterns = @{
        'rwpack' = 'rwpack validate'
        'rw-evidence' = 'rw-evidence kubernetes collect'
    }
    foreach ($commandName in @('rwpack', 'rw-evidence')) {
        $binary = Join-Path $smokeRoot "node_modules/.bin/$commandName$binSuffix"
        if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
            throw "Installed archive did not expose $commandName."
        }
        $helpText = (& $binary --help | Out-String)
        if ($LASTEXITCODE -ne 0 -or $helpText -notmatch [regex]::Escape($helpPatterns[$commandName])) {
            throw "$commandName --help failed after archive installation."
        }
    }

    $rwpack = Join-Path $smokeRoot "node_modules/.bin/rwpack$binSuffix"
    $fixture = Join-Path $workspaceRoot 'fixtures/domain-packs/equipment-fault-test/1.0.0'
    $packedFixture = Join-Path $smokeRoot 'equipment-fault-test.rwpack'
    $installedRoot = Join-Path $smokeRoot 'installed'
    & $rwpack validate $fixture | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Installed rwpack failed to validate a fixture.' }
    & $rwpack pack $fixture --out $packedFixture | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Installed rwpack failed to package a fixture.' }
    & $rwpack verify $packedFixture | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Installed rwpack failed to verify a fixture.' }
    & $rwpack install $packedFixture --root $installedRoot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Installed rwpack failed to install a fixture.' }
    $listed = & $rwpack list --root $installedRoot
    $listedText = $listed | Out-String
    if ($LASTEXITCODE -ne 0 -or $listedText -notmatch 'equipment-fault-test') {
        throw 'Installed rwpack failed to list the installed fixture.'
    }

    $rwEvidence = Join-Path $smokeRoot "node_modules/.bin/rw-evidence$binSuffix"
    $coldFixture = Join-Path $workspaceRoot 'fixtures/cold-holding/zenodo-15130001'
    $coldBundle = Join-Path $smokeRoot 'cold-holding-bundle.json'
    & $rwEvidence cold-holding collect `
        --event-ir (Join-Path $coldFixture 'event-ir.json') `
        --sources (Join-Path $coldFixture 'sources.json') `
        --telemetry (Join-Path $coldFixture 'telemetry.csv') `
        --out $coldBundle | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $coldBundle -PathType Leaf)) {
        throw 'Installed rw-evidence failed to collect the cold-holding fixture.'
    }
    $coldBundleDocument = Get-Content -LiteralPath $coldBundle -Raw | ConvertFrom-Json
    if (@($coldBundleDocument.evidence_items).Count -eq 0) {
        throw 'Installed rw-evidence produced an empty cold-holding bundle.'
    }
}
finally {
    if (Test-Path -LiteralPath $smokeRoot) {
        Remove-Item -LiteralPath $smokeRoot -Recurse -Force
    }
}

Write-Host "CLI release artifacts passed archive and install smoke checks: $outputRoot"
