$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$issues = [System.Collections.Generic.List[string]]::new()

function Add-Issue([string]$message) {
    $issues.Add($message)
}

function Get-RelativeRepositoryPath([string]$path) {
    return [IO.Path]::GetRelativePath($workspaceRoot, $path).Replace([IO.Path]::DirectorySeparatorChar, '/')
}

$requiredPaths = @(
    'ASSET_PROVENANCE.md',
    'CHANGELOG.md',
    'CONTRIBUTING.md',
    '.gitleaks.toml',
    'LICENSE',
    'NOTICE',
    'README.md',
    'SECURITY.md',
    'backend',
    'contracts',
    'design',
    'domain-packs',
    'fixtures',
    'frontend',
    'compose.yml',
    'frontend/Dockerfile',
    'frontend/nginx.conf',
    'infra/compose.test.yml',
    'infra/compose.e2e.yml',
    'infra/postgres/Dockerfile',
    'infra/ollama/Dockerfile',
    'infra/ollama/ensure-model.sh',
    'scripts',
    'tools/domain-pack-cli',
    'tools/domain-pack-cli/README.md',
    'tools/evidence-cli',
    'tools/evidence-cli/README.md',
    'docs/architecture.md',
    'docs/media/reasonweave-demo.gif',
    'docs/media/reasonweave-demo.mp4',
    'docs/screenshots/reasonweave-domain-packs.webp',
    'docs/screenshots/reasonweave-graph.webp',
    'docs/screenshots/reasonweave-investigation.webp',
    'docs/screenshots/reasonweave-retrieval.webp',
    'contracts/openapi/reasonweave-v1.json',
    'scripts/package-cli.ps1',
    'scripts/kubernetes-kind-e2e.sh',
    'scripts/init-local-config.mjs',
    'fixtures/domain-packs/equipment-fault-test/1.0.0/manifest.yaml',
    'domain-packs/kubernetes-pod-diagnostics/1.0.0/manifest.yaml',
    'domain-packs/kubernetes-pod-diagnostics/1.0.0/LICENSES.yaml',
    'domain-packs/kubernetes-pod-diagnostics/1.0.0/NOTICE.md',
    'domain-packs/kubernetes-pod-diagnostics/1.0.0/checksums.sha256',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0/manifest.yaml',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0/event-definitions.yaml',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0/LICENSES.yaml',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0/NOTICE.md',
    'domain-packs/cold-holding-excursion-diagnostics/1.0.0/checksums.sha256',
    'fixtures/cold-holding/zenodo-15130001/README.md',
    'fixtures/cold-holding/zenodo-15130001/event-ir.json',
    'fixtures/cold-holding/zenodo-15130001/sources.json',
    'fixtures/cold-holding/zenodo-15130001/telemetry.csv'
)

foreach ($relativePath in $requiredPaths) {
    if (-not (Test-Path -LiteralPath (Join-Path $workspaceRoot $relativePath))) {
        Add-Issue "missing-required:$relativePath"
    }
}

$contractCopies = @{
    'contracts/eventir/eventir-0.1.schema.json' = 'backend/src/main/resources/contracts/eventir/eventir-0.1.schema.json'
    'contracts/observation-bundle/1.0/observation-bundle.schema.json' = 'backend/src/main/resources/contracts/observation-bundle/observation-bundle-1.schema.json'
    'contracts/domain-pack/1.0/manifest.schema.json' = 'backend/src/main/resources/contracts/domain-pack/manifest-1.schema.json'
}
foreach ($entry in $contractCopies.GetEnumerator()) {
    $source = Join-Path $workspaceRoot $entry.Key
    $copy = Join-Path $workspaceRoot $entry.Value
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or
        -not (Test-Path -LiteralPath $copy -PathType Leaf)) {
        Add-Issue "contract-copy-missing:$($entry.Key)"
        continue
    }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash) {
        Add-Issue "contract-copy-drift:$($entry.Key)"
    }
}
$cliManifestSchema = Join-Path $workspaceRoot 'tools/domain-pack-cli/src/manifest.schema.json'
$canonicalManifestSchema = Join-Path $workspaceRoot 'contracts/domain-pack/1.0/manifest.schema.json'
if (-not (Test-Path -LiteralPath $cliManifestSchema -PathType Leaf) -or
    (Get-FileHash -LiteralPath $cliManifestSchema -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $canonicalManifestSchema -Algorithm SHA256).Hash) {
    Add-Issue 'contract-copy-drift:tools/domain-pack-cli/src/manifest.schema.json'
}

$componentVersions = @{}
if (Test-Path -LiteralPath (Join-Path $workspaceRoot 'package.json')) {
    $rootPackage = Get-Content -Raw -LiteralPath (Join-Path $workspaceRoot 'package.json') | ConvertFrom-Json
    if ($rootPackage.license -ne 'Apache-2.0') { Add-Issue 'license:package.json' }
    $componentVersions['package.json'] = $rootPackage.version
}
if (Test-Path -LiteralPath (Join-Path $workspaceRoot 'frontend/package.json')) {
    $frontendPackage = Get-Content -Raw -LiteralPath (Join-Path $workspaceRoot 'frontend/package.json') | ConvertFrom-Json
    if ($frontendPackage.license -ne 'Apache-2.0') { Add-Issue 'license:frontend/package.json' }
    $componentVersions['frontend/package.json'] = $frontendPackage.version
}
foreach ($cliPackagePath in @('tools/domain-pack-cli/package.json', 'tools/evidence-cli/package.json')) {
    $absolutePackagePath = Join-Path $workspaceRoot $cliPackagePath
    if (-not (Test-Path -LiteralPath $absolutePackagePath -PathType Leaf)) { continue }
    $cliPackage = Get-Content -Raw -LiteralPath $absolutePackagePath | ConvertFrom-Json
    if ($cliPackage.license -ne 'Apache-2.0') { Add-Issue "license:$cliPackagePath" }
    if ($cliPackage.private -eq $true) { Add-Issue "package-private:$cliPackagePath" }
    if ($null -eq $cliPackage.files -or 'README.md' -notin $cliPackage.files) {
        Add-Issue "package-files:$cliPackagePath"
    }
    if ($cliPackage.engines.node -ne '>=22') { Add-Issue "package-node-engine:$cliPackagePath" }
    $componentVersions[$cliPackagePath] = $cliPackage.version
}
if (Test-Path -LiteralPath (Join-Path $workspaceRoot 'backend/pom.xml')) {
    [xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $workspaceRoot 'backend/pom.xml')
    $namespace = [Xml.XmlNamespaceManager]::new($pom.NameTable)
    $namespace.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $pomLicense = $pom.SelectSingleNode('/m:project/m:licenses/m:license/m:name', $namespace)
    if ($null -eq $pomLicense -or $pomLicense.InnerText -ne 'Apache License, Version 2.0') {
        Add-Issue 'license:backend/pom.xml'
    }
    $pomVersion = $pom.SelectSingleNode('/m:project/m:version', $namespace)
    if ($null -ne $pomVersion) { $componentVersions['backend/pom.xml'] = $pomVersion.InnerText }
}
foreach ($entry in $componentVersions.GetEnumerator()) {
    if ($entry.Value -ne '0.4.1') { Add-Issue "version:$($entry.Key):$($entry.Value)" }
}

$versionedArtifacts = @(
    'backend/.dockerignore',
    'backend/Dockerfile',
    'backend/Dockerfile.runtime'
)
foreach ($relativePath in $versionedArtifacts) {
    $path = Join-Path $workspaceRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $content = Get-Content -Raw -LiteralPath $path
    if ($content -match '0\.4\.1-SNAPSHOT') { Add-Issue "snapshot-artifact:$relativePath" }
    if (($relativePath -like '*Dockerfile*' -or $relativePath -eq 'backend/.dockerignore') -and
        $content -notmatch 'reasonweave-backend-0\.4\.1\.jar') {
        Add-Issue "backend-artifact-version:$relativePath"
    }
}

foreach ($script in Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'scripts') -File |
    Where-Object { $_.Extension -in @('.ps1', '.sh') }) {
    $scriptContent = Get-Content -Raw -LiteralPath $script.FullName
    if ($scriptContent -match '(?m)&\s+rtk\b') {
        Add-Issue "local-tool-dependency:$($script.Name)"
    }
}
$productionDomainPacks = @{
    'kubernetes' = 'domain-packs/kubernetes-pod-diagnostics/1.0.0/manifest.yaml'
    'cold-holding' = 'domain-packs/cold-holding-excursion-diagnostics/1.0.0/manifest.yaml'
}
foreach ($entry in $productionDomainPacks.GetEnumerator()) {
    $domainManifestPath = Join-Path $workspaceRoot $entry.Value
    if (Test-Path -LiteralPath $domainManifestPath) {
        $domainManifest = Get-Content -Raw -LiteralPath $domainManifestPath
        if ($domainManifest -notmatch '(?m)^production_allowed:\s*true\s*$') {
            Add-Issue "domain-pack:$($entry.Key)-production-not-allowed"
        }
        if ($domainManifest -match '(?m)^fixture_only:\s*true\s*$') {
            Add-Issue "domain-pack:$($entry.Key)-marked-fixture"
        }
        if ($domainManifest -notmatch '(?m)^vector_policy:\s*required\s*$') {
            Add-Issue "domain-pack:$($entry.Key)-vector-not-required"
        }
    }
}
if (Test-Path -LiteralPath (Join-Path $workspaceRoot 'domain-packs/cargo-damage')) {
    Add-Issue 'domain-pack:cargo-removed-from-runtime'
}
if (Test-Path -LiteralPath (Join-Path $workspaceRoot 'fixtures/domain-packs/cargo-damage')) {
    Add-Issue 'domain-pack:cargo-fixture-must-be-removed'
}

$provenancePath = Join-Path $workspaceRoot 'ASSET_PROVENANCE.md'
$declaredAssets = @{}
if (Test-Path -LiteralPath $provenancePath) {
    foreach ($line in Get-Content -LiteralPath $provenancePath) {
        if ($line -notmatch '^([0-9a-f]{64})\s{2}(.+)$') { continue }
        $expectedHash = $matches[1]
        $relativePath = $matches[2].Trim().Replace('\', '/')
        if ($declaredAssets.ContainsKey($relativePath)) {
            Add-Issue "asset-duplicate:$relativePath"
            continue
        }
        $declaredAssets[$relativePath] = $expectedHash

        $assetPath = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $relativePath))
        $workspacePrefix = [IO.Path]::GetFullPath($workspaceRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $assetPath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            Add-Issue "asset-path:$relativePath"
            continue
        }
        if (-not (Test-Path -LiteralPath $assetPath -PathType Leaf)) {
            Add-Issue "asset-missing:$relativePath"
            continue
        }
        $actualHash = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) { Add-Issue "asset-checksum:$relativePath" }
    }
}

$assetFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'design/assets') -File
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'frontend/public/brand') -File
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'frontend/public/icons') -File
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'fixtures/evidence') -File
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'docs/media') -File
    Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'docs/screenshots') -File
) | ForEach-Object { Get-RelativeRepositoryPath $_.FullName } | Sort-Object -Unique

foreach ($relativePath in $assetFiles) {
    if (-not $declaredAssets.ContainsKey($relativePath)) { Add-Issue "asset-undocumented:$relativePath" }
}
foreach ($relativePath in $declaredAssets.Keys) {
    if ($relativePath -notin $assetFiles) { Add-Issue "asset-out-of-scope:$relativePath" }
}

$composePath = Join-Path $workspaceRoot 'compose.yml'
if (Test-Path -LiteralPath $composePath -PathType Leaf) {
    $compose = Get-Content -Raw -LiteralPath $composePath
    if (($compose | Select-String -Pattern '(?m)^\s{4}ports:\s*$' -AllMatches).Matches.Count -ne 1) {
        Add-Issue 'compose:only-frontend-may-publish-ports'
    }
    if ($compose -notmatch '127\.0\.0\.1:\$\{RW_HTTP_PORT:-8080\}:8080') {
        Add-Issue 'compose:frontend-not-loopback-bound'
    }
    if ($compose -match '(?m)^\s*container_name:') {
        Add-Issue 'compose:global-container-name'
    }
    if (-not $compose.Contains('name: reasonweave-ollama-model-cache') -or
        -not $compose.Contains('external: true')) {
        Add-Issue 'compose:model-cache-must-be-external'
    }
    if (-not $compose.Contains('infra/postgres/Dockerfile') -or
        -not $compose.Contains('infra/ollama/Dockerfile') -or
        -not $compose.Contains('reasonweave-ensure-model') -or
        $compose.Contains('pgvector/pgvector:pg16')) {
        Add-Issue 'compose:hardened-runtime-images-required'
    }
}

$hardenedImages = @{
    'infra/postgres/Dockerfile' = @('PGVECTOR_SHA256=', 'apk upgrade --no-cache', 'USER postgres')
    'infra/ollama/Dockerfile' = @('OLLAMA_SOURCE_SHA256=', 'GO_SHA256=', 'golang.org/x/crypto@v0.53.0', 'USER 10001:10001')
    'frontend/Dockerfile' = @('nginx:1.31.4-alpine3.24', 'apk upgrade --no-cache', 'USER nginx')
}
foreach ($entry in $hardenedImages.GetEnumerator()) {
    $path = Join-Path $workspaceRoot $entry.Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $content = Get-Content -Raw -LiteralPath $path
    foreach ($marker in $entry.Value) {
        if (-not $content.Contains($marker)) { Add-Issue "image-hardening:$($entry.Key):$marker" }
    }
}

$e2eComposePath = Join-Path $workspaceRoot 'infra/compose.e2e.yml'
if (Test-Path -LiteralPath $e2eComposePath -PathType Leaf) {
    $e2eCompose = Get-Content -Raw -LiteralPath $e2eComposePath
    if (-not $e2eCompose.Contains('name: reasonweave-ollama-model-cache') -or
        -not $e2eCompose.Contains('external: true')) {
        Add-Issue 'compose-e2e:model-cache-must-be-external'
    }
}

$localInitPath = Join-Path $workspaceRoot 'scripts/init-local-config.mjs'
if (Test-Path -LiteralPath $localInitPath -PathType Leaf) {
    $localInit = Get-Content -Raw -LiteralPath $localInitPath
    if (-not $localInit.Contains('mode: 0o700') -or
        -not $localInit.Contains("open(passwordPath, 'wx', 0o644)") -or
        -not $localInit.Contains('chmod(secretDirectory, 0o700)') -or
        -not $localInit.Contains('chmod(passwordPath, 0o644)')) {
        Add-Issue 'local-init:compose-secret-permissions'
    }
}

$openApiPath = Join-Path $workspaceRoot 'contracts/openapi/reasonweave-v1.json'
if (Test-Path -LiteralPath $openApiPath -PathType Leaf) {
    try {
        $openApiRaw = Get-Content -Raw -LiteralPath $openApiPath
        $openApi = $openApiRaw | ConvertFrom-Json -AsHashtable
        if ($openApi.openapi -notmatch '^3\.') { Add-Issue 'openapi:unsupported-version' }
        if ($openApi.info.title -ne 'ReasonWeave API' -or $openApi.info.version -ne 'v1') {
            Add-Issue 'openapi:identity-mismatch'
        }
        if ($openApi.ContainsKey('servers')) { Add-Issue 'openapi:export-listener-leaked' }
        if ($openApi.paths.ContainsKey('/api/v1/session')) { Add-Issue 'openapi:session-must-not-exist' }
        if (-not $openApi.paths.ContainsKey('/api/v1/runtime')) { Add-Issue 'openapi:runtime-missing' }
        if ($openApiRaw.Contains('workspace_id')) { Add-Issue 'openapi:workspace-field-must-not-exist' }
        if ($openApi.components.schemas.EventTypeView.properties.evidence_inputs.type -ne 'array') {
            Add-Issue 'openapi:evidence-inputs-must-be-structured'
        }
        if ($openApi.components.schemas.DomainPackDetail.properties.event_definitions.type -ne 'array') {
            Add-Issue 'openapi:event-definitions-must-be-structured'
        }
        if ($openApi.components.schemas.EventTypeView.properties.event_requirements.'$ref' -ne '#/components/schemas/EventRequirementsView' -or
            $openApi.components.schemas.EventRequirementsView.properties.time_range.type -ne 'string') {
            Add-Issue 'openapi:event-time-range-requirement-missing'
        }
    }
    catch {
        Add-Issue 'openapi:invalid-json'
    }
}

$domainNeutralSearchRoots = @(
    'backend/src/main/java',
    'frontend/src'
)
$kubernetesTerm = '(?i)kubernetes|k8s|kubectl|pod_name|namespace'
foreach ($relativeRoot in $domainNeutralSearchRoots) {
    $absoluteRoot = Join-Path $workspaceRoot $relativeRoot
    foreach ($file in Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File) {
        $relativePath = Get-RelativeRepositoryPath $file.FullName
        if ($relativePath -eq 'frontend/src/api/schema.d.ts') { continue }
        if ($file.Name -match '\.test\.[^.]+$') { continue }
        if ((Get-Content -Raw -LiteralPath $file.FullName) -match $kubernetesTerm) {
            Add-Issue "domain-coupling:$relativePath"
        }
    }
}

$ignoreProbes = @(
    '.artifacts/.open-source-probe',
    '.pnpm-store/.open-source-probe',
    'backend/target/.open-source-probe',
    'frontend/dist/.open-source-probe',
    'frontend/test-results/.open-source-probe',
    'frontend/playwright-report/.open-source-probe',
    'frontend/output/playwright/.open-source-probe',
    'output/playwright/.open-source-probe',
    '.local/.open-source-probe',
    'node_modules/.open-source-probe',
    'tools/domain-pack-cli/node_modules/.open-source-probe',
    'tools/evidence-cli/node_modules/.open-source-probe'
)
foreach ($probe in $ignoreProbes) {
    & git -C $workspaceRoot check-ignore --quiet --no-index -- $probe
    if ($LASTEXITCODE -ne 0) { Add-Issue "not-ignored:$probe" }
}

$candidateFiles = @(& git -C $workspaceRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Unable to enumerate the open-source candidate with git.' }
$forbiddenPrefixes = @('.artifacts/', '.local/', '.pnpm-store/', 'backend/target/', 'frontend/dist/', 'frontend/test-results/', 'frontend/playwright-report/', 'frontend/output/playwright/', 'output/playwright/')
foreach ($candidate in $candidateFiles) {
    $normalized = $candidate.Replace('\', '/')
    if ($normalized.StartsWith('node_modules/', [StringComparison]::OrdinalIgnoreCase) -or
        $normalized.Contains('/node_modules/', [StringComparison]::OrdinalIgnoreCase)) {
        Add-Issue "candidate-generated:$normalized"
        continue
    }
    foreach ($prefix in $forbiddenPrefixes) {
        if ($normalized.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            Add-Issue "candidate-generated:$normalized"
            break
        }
    }
}

$textExtensions = @('.css', '.d.ts', '.html', '.java', '.js', '.json', '.md', '.mjs', '.properties', '.ps1', '.sh', '.sql', '.svg', '.ts', '.tsx', '.txt', '.xml', '.yaml', '.yml')
$textNames = @('.gitattributes', '.gitignore', 'Dockerfile', 'LICENSE', 'NOTICE', 'mvnw')
$forbiddenText = @(
    @{ Name = 'absolute-file-uri'; Pattern = '(?i)\bfile:[a-z]:[\\/]' },
    @{ Name = 'personal-windows-home'; Pattern = '(?i)[a-z]:[\\/](?:Users|Documents and Settings)[\\/]' },
    @{ Name = 'local-windows-workspace'; Pattern = '(?i)(?<![A-Za-z0-9_.-])[a-z]:[\\/](?:project|projects|repos|repository|workspace|src)[\\/]' },
    @{ Name = 'personal-posix-home'; Pattern = '(?i)/(?:home|Users)/[A-Za-z0-9._-]+/(?:Desktop|Documents|Downloads|Projects?|Repos?|repository|workspace|src)/' },
    @{ Name = 'private-key'; Pattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' },
    @{ Name = 'openai-style-secret'; Pattern = '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}' },
    @{ Name = 'aws-access-key'; Pattern = '(?<![A-Z0-9])AKIA[A-Z0-9]{16}(?![A-Z0-9])' }
)

$denylistPath = $env:RW_PUBLICATION_DENYLIST
if (-not [string]::IsNullOrWhiteSpace($denylistPath)) {
    $resolvedDenylist = [IO.Path]::GetFullPath($denylistPath)
    $workspaceBoundary = [IO.Path]::GetFullPath($workspaceRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedDenylist.StartsWith($workspaceBoundary, [StringComparison]::OrdinalIgnoreCase)) {
        Add-Issue 'publication-denylist:must-be-outside-repository'
    } elseif (-not (Test-Path -LiteralPath $resolvedDenylist -PathType Leaf)) {
        Add-Issue 'publication-denylist:not-found'
    } else {
        $denylistIndex = 0
        foreach ($line in Get-Content -LiteralPath $resolvedDenylist) {
            $pattern = $line.Trim()
            if ([string]::IsNullOrWhiteSpace($pattern) -or $pattern.StartsWith('#')) { continue }
            $denylistIndex++
            try {
                [void][regex]::new($pattern)
                $forbiddenText += @{ Name = "local-denylist-$denylistIndex"; Pattern = $pattern }
            } catch {
                Add-Issue "publication-denylist:invalid-pattern:$denylistIndex"
            }
        }
    }
}

$nonPublicOperationalPath = '(?i)^(?:scripts|infra)/(?:[^/]*(?:deploy|release|restore|tunnel|remote|ssh|verify-live|server)[^/]*)(?:/|$)'
foreach ($candidate in $candidateFiles) {
    $normalized = $candidate.Replace('\', '/')
    if ($normalized -match $nonPublicOperationalPath) {
        Add-Issue "environment-specific-operation:$normalized"
    }
}
foreach ($candidate in $candidateFiles) {
    $candidatePath = Join-Path $workspaceRoot $candidate
    if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) { continue }
    $item = Get-Item -LiteralPath $candidatePath
    if ($item.Length -gt 5MB) { continue }
    $extension = if ($item.Name.EndsWith('.d.ts')) { '.d.ts' } else { $item.Extension.ToLowerInvariant() }
    if ($extension -notin $textExtensions -and $item.Name -notin $textNames) { continue }
    $content = Get-Content -Raw -LiteralPath $candidatePath
    foreach ($rule in $forbiddenText) {
        if ($content -match $rule.Pattern) { Add-Issue "$($rule.Name):$candidate" }
    }

    foreach ($match in [regex]::Matches($content, '(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])')) {
        $address = $null
        if (-not [Net.IPAddress]::TryParse($match.Value, [ref]$address)) { continue }
        $octets = $address.GetAddressBytes()
        $isNonPublic = $octets[0] -eq 0 -or
            $octets[0] -eq 10 -or
            $octets[0] -eq 127 -or
            ($octets[0] -eq 100 -and $octets[1] -ge 64 -and $octets[1] -le 127) -or
            ($octets[0] -eq 169 -and $octets[1] -eq 254) -or
            ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or
            ($octets[0] -eq 192 -and $octets[1] -eq 168) -or
            $octets[0] -ge 224
        if (-not $isNonPublic) { Add-Issue "public-ipv4:$candidate" }
    }
}

if ($issues.Count -gt 0) {
    throw "Open-source readiness verification failed: $($issues -join ', ')"
}

Write-Host "Open-source readiness verified: $($candidateFiles.Count) candidate files, $($declaredAssets.Count) documented assets."
