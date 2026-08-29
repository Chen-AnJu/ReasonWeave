# API quick start

This guide exercises the public ReasonWeave flow without using the console.
It assumes the standard Compose entry point is available at
`http://127.0.0.1:8080`. Responses use `{data, meta}`; keep the returned IDs and
the `meta.request_id` values rather than copying the placeholders below.

## 1. Discover the runtime contract

```shell
curl --fail-with-body http://127.0.0.1:8080/api/v1/runtime
curl --fail-with-body http://127.0.0.1:8080/api/v1/domain-packs
curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/domain-packs/kubernetes-pod-diagnostics/versions/1.0.0/event-types/kubernetes_pod_failure
curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/domain-packs/cold-holding-excursion-diagnostics/versions/1.0.0/event-types/cold_holding_temperature_excursion
```

The event-type response is authoritative for the subject schema, identity
fields, evidence inputs, target-version policy, and presentation metadata. API
clients should discover these values instead of hard-coding industry fields.
The `event_requirements.time_range` value is authoritative; the cold-holding
event requires both RFC 3339 start and end timestamps.

## 2. Create an event

`Idempotency-Key` is required. Reusing the same key with the same canonical
request returns the original resource; reusing it for different content
returns `409`.

```shell
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: quickstart-event-001' \
  --data-binary @- <<'JSON'
{
  "event_ir": {
    "schema_version": "eventir/0.1",
    "event": {
      "type": "kubernetes_pod_failure",
      "title": "payment-api image pull failure",
      "reference_code": "QS-POD-001",
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

Save `data.id` as `<event-id>`. The payload is checked against both EventIR 0.1
and the selected Domain Pack's event schema.

## 3. Import and review observations

For Kubernetes, prefer producing this payload with the read-only collector.
The example below shows the same standard `observation-bundle/1.0` contract
that any external collector may generate.

```shell
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/events/<event-id>/evidence/bundles \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON'
{
  "schema_version": "observation-bundle/1.0",
  "domain_pack": "kubernetes-pod-diagnostics/1.0.0",
  "event_type": "kubernetes_pod_failure",
  "target_version": "1.37.0",
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
      "captured_at": "2026-08-28T08:05:00Z",
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

Every imported Observation starts as `PENDING`. Save its `id` and `version`,
then confirm it explicitly:

```shell
curl --fail-with-body \
  -X PATCH http://127.0.0.1:8080/api/v1/observations/<observation-id> \
  -H 'Content-Type: application/json' \
  -H 'If-Match: <observation-version>' \
  --data '{"verification_status":"CONFIRMED"}'
```

An invalid subject, source profile, Predicate, value, or target version rejects
the whole Bundle; partial evidence is not persisted.

For a cold-holding event, first save the exact EventIR used to create the event,
then produce the same standard Bundle locally:

```shell
rw-evidence cold-holding collect \
  --event-ir event-ir.json \
  --sources sources.json \
  --telemetry telemetry.csv \
  --out cold-holding-bundle.json
```

The collector uses only the EventIR time window and site-supplied thresholds.
It does not decide food safety, disposal, HACCP status, regulatory compliance,
or repairs. Import and confirm its pending Observations through the same API
shown above.

## 4. Run and inspect an investigation

Production packs with `vector_policy=required` require a ready real embedding
index. A Mock provider or unavailable/mismatched model intentionally prevents
the operation instead of silently degrading it.

```shell
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/events/<event-id>/investigations \
  -H 'Idempotency-Key: quickstart-investigation-001'

curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/investigations/<investigation-id>
curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/investigations/<investigation-id>/next-evidence
curl --fail-with-body \
  'http://127.0.0.1:8080/api/v1/events/<event-id>/graph?investigation_id=<investigation-id>'
curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/events/<event-id>/audit
```

The result is an immutable run snapshot. Knowledge citations explain and
constrain hypotheses but never add to the evidence support score. The support
index is deterministic and is not a probability or an automatic root-cause
decision.

## Contract and errors

The versioned machine-readable contract is
[`contracts/openapi/reasonweave-v1.json`](../contracts/openapi/reasonweave-v1.json).
Errors use `{error, meta}` and preserve `meta.request_id`. `400` indicates a
contract or domain mismatch, `409` an idempotency/version conflict, `415` an
unsupported or mismatched media type, and `502` a persisted provider failure.
