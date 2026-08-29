# @reasonweave/evidence-cli

`rw-evidence` creates privacy-bounded Observation Bundles for ReasonWeave.
Version 0.4.1 includes collectors for Kubernetes Pod status and cold-holding
telemetry. Collectors run locally and do not upload their inputs.

```console
rw-evidence kubernetes collect --namespace default --pod example-pod \
  --anonymize --out pod-observations.json
```

The collector uses read-only `kubectl get` and `kubectl version` calls. It does
not collect Secret values, environment-variable values, ServiceAccount tokens,
or complete container logs. Review generated bundles before importing them.

The cold-holding collector accepts an EventIR with a complete time range, a
source registry, and an RFC 4180 long-table CSV:

```console
rw-evidence cold-holding collect \
  --event-ir event-ir.json \
  --sources sources.json \
  --telemetry telemetry.csv \
  --out cold-holding-bundle.json
```

The CSV columns are `timestamp,source_id,metric,value,unit`. Timestamps must be
RFC 3339 with an explicit offset; temperature is Celsius only. The collector
rejects unknown metrics, conflicting duplicates, out-of-window rows, files over
250 MiB, and inputs over 5,000,000 records. It emits only deterministic summary
Observations, source IDs, row ranges, input SHA-256 values, the event window,
and the algorithm version. It never embeds the full telemetry file and never
overwrites an existing output file.

All generated Observations start as `PENDING` after API import. This collector
diagnoses operational evidence only; it does not decide food safety, disposal,
regulatory compliance, HACCP certification, or repairs.

License: Apache-2.0.
