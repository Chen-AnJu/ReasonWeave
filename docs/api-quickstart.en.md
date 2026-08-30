# API Quick Start

[中文](api-quickstart.md) · [Back to README](../README.en.md)

This guide completes the public ReasonWeave flow without the console. It assumes the standard Compose entry point is available at `http://127.0.0.1:8080`. Responses use `{data, meta}` or `{error, meta}`. Save returned IDs and `meta.request_id` values instead of copying placeholders from this document.

## 1. Verify the runtime and discover domain capabilities

### Bash / curl

```bash
BASE_URL=http://127.0.0.1:8080
curl --fail-with-body "$BASE_URL/api/v1/runtime"
curl --fail-with-body "$BASE_URL/api/v1/domain-packs"
curl --fail-with-body \
  "$BASE_URL/api/v1/domain-packs/kubernetes-pod-diagnostics/versions/1.0.0/event-types/kubernetes_pod_failure"
```

### PowerShell

```powershell
$BaseUrl = 'http://127.0.0.1:8080'
Invoke-RestMethod "$BaseUrl/api/v1/runtime"
Invoke-RestMethod "$BaseUrl/api/v1/domain-packs"
Invoke-RestMethod "$BaseUrl/api/v1/domain-packs/kubernetes-pod-diagnostics/versions/1.0.0/event-types/kubernetes_pod_failure"
```

The event definition is authoritative for subject schemas, identity fields, evidence inputs, target versions, and presentation metadata. Generic clients must discover these values rather than hard-code Kubernetes or cold-holding fields.

## 2. Create an event

`Idempotency-Key` is required. Reusing the key for the same canonical request returns the original resource; reusing it for different content returns `409`.

```bash
curl --fail-with-body \
  -X POST "$BASE_URL/api/v1/events" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: quickstart-event-001' \
  --data-binary @- <<'JSON'
{
  "event_ir": {
    "schema_version": "eventir/0.1",
    "event": {
      "type": "kubernetes_pod_failure",
      "title": "payment-api image pull failure",
      "reference_code": "EVT-QS-POD-001",
      "domain_pack": "kubernetes-pod-diagnostics/1.0.0"
    },
    "subjects": [
      {
        "type": "kubernetes_pod",
        "label": "default/payment-api",
        "attributes": {
          "namespace": "default",
          "pod_name": "payment-api"
        }
      }
    ],
    "claims": [],
    "evidence": [],
    "observations": [],
    "hypotheses": [],
    "contradictions": [],
    "unknowns": []
  }
}
JSON
```

Save `data.id` as `<event-id>`. The request is validated against EventIR 0.1 and then against the selected Domain Pack's event schema.

## 3. Import and review Observations

The payload below is the standard `observation-bundle/1.0` that any external collector can produce. Prefer the read-only `rw-evidence kubernetes collect` collector for live Kubernetes inputs.

```bash
curl --fail-with-body \
  -X POST "$BASE_URL/api/v1/events/<event-id>/evidence/bundles" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON'
{
  "schema_version": "observation-bundle/1.0",
  "domain_pack": "kubernetes-pod-diagnostics/1.0.0",
  "event_type": "kubernetes_pod_failure",
  "target_version": "v1.37.0",
  "subject": {
    "type": "kubernetes_pod",
    "label": "default/payment-api",
    "attributes": {
      "namespace": "default",
      "pod_name": "payment-api"
    }
  },
  "evidence_items": [
    {
      "external_id": "quickstart-pod-status-001",
      "source_type": "kubernetes_api",
      "captured_at": "2026-08-30T08:00:00Z",
      "observations": [
        {
          "predicate": "image_pull_backoff",
          "value": true,
          "confidence": 1.0,
          "source_locator": {
            "kind": "Pod",
            "field": "status.containerStatuses.state.waiting.reason"
          }
        }
      ]
    }
  ]
}
JSON
```

New Observations always start as `PENDING`. Save each returned Observation `id` and `version`, then confirm it explicitly:

```bash
curl --fail-with-body \
  -X PATCH "$BASE_URL/api/v1/observations/<observation-id>" \
  -H 'Content-Type: application/json' \
  -H 'If-Match: <observation-version>' \
  --data '{"verification_status":"CONFIRMED"}'
```

An invalid subject, source profile, predicate, value schema, or target version rejects the entire Bundle. Partial evidence is never persisted.

## 4. Run and inspect an investigation

```bash
curl --fail-with-body \
  -X POST "$BASE_URL/api/v1/events/<event-id>/investigations" \
  -H 'Idempotency-Key: quickstart-investigation-001'

curl --fail-with-body "$BASE_URL/api/v1/investigations/<investigation-id>"
curl --fail-with-body "$BASE_URL/api/v1/investigations/<investigation-id>/next-evidence"
curl --fail-with-body \
  "$BASE_URL/api/v1/events/<event-id>/graph?investigation_id=<investigation-id>"
curl --fail-with-body "$BASE_URL/api/v1/events/<event-id>/audit"
```

The result is an immutable Run snapshot. New evidence makes an old Run stale and a new investigation creates a new Run; it never rewrites the old result.

## PowerShell: complete executable Kubernetes flow

The following script captures every ID, version, and idempotency key:

```powershell
$BaseUrl = 'http://127.0.0.1:8080'
$Suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$PodName = "image-pull-$Suffix"

$EventIr = @{
  schema_version = 'eventir/0.1'
  event = @{
    type = 'kubernetes_pod_failure'
    title = 'Quickstart image pull failure'
    reference_code = "EVT-QS-$Suffix"
    domain_pack = 'kubernetes-pod-diagnostics/1.0.0'
  }
  subjects = @(@{
    type = 'kubernetes_pod'
    label = "default/$PodName"
    attributes = @{ namespace = 'default'; pod_name = $PodName }
  })
  claims = @(); evidence = @(); observations = @()
  hypotheses = @(); contradictions = @(); unknowns = @()
}

$EventResponse = Invoke-RestMethod -Method Post "$BaseUrl/api/v1/events" `
  -Headers @{ 'Idempotency-Key' = "quickstart-event-$Suffix" } `
  -ContentType 'application/json' `
  -Body (@{ event_ir = $EventIr } | ConvertTo-Json -Depth 20)
$EventId = $EventResponse.data.id

$Bundle = @{
  schema_version = 'observation-bundle/1.0'
  domain_pack = 'kubernetes-pod-diagnostics/1.0.0'
  event_type = 'kubernetes_pod_failure'
  target_version = 'v1.37.0'
  subject = @{
    type = 'kubernetes_pod'
    label = "default/$PodName"
    attributes = @{ namespace = 'default'; pod_name = $PodName }
  }
  evidence_items = @(@{
    external_id = "quickstart:$Suffix"
    source_type = 'kubernetes_api'
    captured_at = [DateTimeOffset]::UtcNow.ToString('o')
    observations = @(@{
      predicate = 'image_pull_backoff'
      value = $true
      confidence = 1.0
      source_locator = @{
        kind = 'Pod'
        namespace = 'default'
        field = 'status.containerStatuses[].state.waiting.reason'
      }
    })
  })
}

$Imported = Invoke-RestMethod -Method Post `
  "$BaseUrl/api/v1/events/$EventId/evidence/bundles" `
  -ContentType 'application/json' `
  -Body ($Bundle | ConvertTo-Json -Depth 20)

foreach ($EvidenceItem in $Imported.data.evidence) {
  foreach ($Observation in $EvidenceItem.observations) {
    Invoke-RestMethod -Method Patch `
      "$BaseUrl/api/v1/observations/$($Observation.id)" `
      -Headers @{ 'If-Match' = [string]$Observation.version } `
      -ContentType 'application/json' `
      -Body '{"verification_status":"CONFIRMED"}' | Out-Null
  }
}

$RunResponse = Invoke-RestMethod -Method Post `
  "$BaseUrl/api/v1/events/$EventId/investigations" `
  -Headers @{ 'Idempotency-Key' = "quickstart-run-$Suffix" }
$RunId = $RunResponse.data.id

(Invoke-RestMethod "$BaseUrl/api/v1/investigations/$RunId").data
Invoke-RestMethod "$BaseUrl/api/v1/investigations/$RunId/next-evidence"
Invoke-RestMethod "$BaseUrl/api/v1/events/$EventId/graph?investigation_id=$RunId"
Invoke-RestMethod "$BaseUrl/api/v1/events/$EventId/audit"
```

## Verified success result

The normalized Golden Investigation for this symptom is:

```json
{
  "top_hypothesis": "Image acquisition failure",
  "support_index": 64,
  "coverage": 0.3571,
  "grounding_status": "GROUNDED",
  "scoring_evidence": [
    { "predicate": "image_pull_backoff", "contribution": 0.95 }
  ],
  "citations": [
    { "section": "Container image acquisition failures", "score_affecting": false },
    { "section": "Observable states", "score_affecting": false }
  ]
}
```

The support index is a deterministic rule output, not a probability or automatic root-cause decision. See [`docs/examples/kubernetes-investigation-summary.json`](examples/kubernetes-investigation-summary.json) for the complete normalized result.

## Error response and Request ID

For example, an empty event request returns `400`:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请求字段校验失败",
    "details": { "eventIr": "must not be null" }
  },
  "meta": { "request_id": "req_..." }
}
```

Typical statuses are `400` for contract/domain mismatches, `409` for idempotency or version conflicts, `415` for unsupported or mismatched content, and `502` for a persisted Provider failure. Include a sanitized `meta.request_id` in reports; never post passwords, production evidence, or private network information.

## Cold-holding scenario

Cold-holding events require `occurred_at.start/end` and site-supplied temperature, duration, sample-gap, and sensor-tolerance thresholds. The collector reads only local CSV rows inside that window:

```bash
rw-evidence cold-holding collect \
  --event-ir event-ir.json \
  --sources sources.json \
  --telemetry telemetry.csv \
  --out cold-holding-bundle.json
```

The resulting Bundle uses the same import, review, investigation, graph, and audit APIs. It diagnoses operational causes and does not decide whether food may be used, sold, or consumed.

## Contract

The versioned machine contract is [`contracts/openapi/reasonweave-v1.json`](../contracts/openapi/reasonweave-v1.json). Event fields are defined by the event definition returned from the running instance. Clients should not maintain a second industry-specific Wire DTO set.
