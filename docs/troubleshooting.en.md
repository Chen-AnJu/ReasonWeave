# Troubleshooting

Run commands from the repository root. The preview must stay loopback-bound; never expose the unauthenticated API to the public Internet as a troubleshooting shortcut.

## Model download is slow

The first start downloads `qwen3-embedding:0.6b` into `reasonweave-ollama-model-cache`:

```console
docker compose logs -f ollama-model
```

The named volume survives `docker compose down` and normal restarts. The model downloads again only after that volume is explicitly removed.

- A first download logs `Ollama model cache miss; pulling once` and the 639 MB progress.
- A cached start logs `Ollama model cache hit` and does not pull the model again.

The reference acceptance link downloaded into an empty cache in 164.74 seconds at roughly 3.6–4.5 MB/s and occupied about 609.6 MiB on disk. Your result is dominated by network performance to the Ollama model registry. Do not delete the model volume merely because progress pauses briefly.

## Containers run out of memory

The complete stack includes PostgreSQL, Ollama, the backend, and the frontend. Keep at least 4 GiB available; 6 GiB or more is recommended for real vector retrieval.

```console
docker compose ps
docker compose logs --tail=200 ollama backend
```

Do not silently switch production packs to a Mock provider. ReasonWeave deliberately marks the index unready and blocks an investigation when the real embedding contract is unavailable.

## Port 8080 is busy

POSIX shell:

```console
RW_HTTP_PORT=8088 docker compose up -d
```

PowerShell:

```powershell
$env:RW_HTTP_PORT = '8088'
docker compose up -d
```

Open `http://127.0.0.1:8088`. Do not publish backend, PostgreSQL, or Ollama ports.

## A Domain Pack reports an unready index

```console
docker compose ps
docker compose logs ollama-model
docker compose logs --tail=200 backend
curl --fail-with-body http://127.0.0.1:8080/api/v1/domain-packs
```

Typical causes are an unfinished model download, a model-digest mismatch, a dimension other than 1024, or an unfinished vector index. The engine never labels an FTS-only fallback as production hybrid retrieval.

## Domain Pack checksum drift

The repository `.gitattributes` pins pack, fixture, Schema, Markdown, JSON, YAML, and checksum files to LF. Do not let an editor rewrite their line endings.

```console
pnpm rwpack validate domain-packs/kubernetes-pod-diagnostics/1.0.0
pnpm rwpack validate domain-packs/cold-holding-excursion-diagnostics/1.0.0
```

An intentional content change requires a new pack version and the complete validation, licensing, and Golden Fixture flow. Do not overwrite only the old checksum file.

## Remove local volumes

`docker compose down` preserves data. The following command permanently deletes the current Compose project's database and Blob volumes; back up anything important first:

```console
docker compose down -v
```

The fixed `reasonweave-ollama-model-cache` volume is normally retained. Removing it forces a new model download.

## Report useful evidence safely

Include the image tag/digest, a minimal reproduction, `meta.request_id`, and sanitized logs. Never post passwords, production evidence, kubeconfig content, complete telemetry, or private network details. See [SUPPORT.md](../SUPPORT.md) and [SECURITY.md](../SECURITY.md).
