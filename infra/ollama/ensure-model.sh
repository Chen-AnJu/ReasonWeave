#!/bin/sh
set -eu

model=${1:?usage: reasonweave-ensure-model MODEL}
if ollama show "$model" >/dev/null 2>&1; then
  printf 'Ollama model cache hit: %s\n' "$model"
  exit 0
fi

printf 'Ollama model cache miss; pulling once: %s\n' "$model"
exec ollama pull "$model"
