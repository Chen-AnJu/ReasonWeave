FROM ubuntu:24.04 AS build

ARG OLLAMA_VERSION=0.33.1
ARG OLLAMA_SOURCE_SHA256=8f47ee4029470b4cd4a338dd6eafec4181bed46c6d180bed02f4127b43f2aab5
ARG GO_VERSION=1.26.7
ARG GO_SHA256=ffb5f8de10c62550dfddab66b36b57030721e0a44a3218e9e1181d7b59f121ca

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl --fail --location --retry 5 --retry-all-errors \
      --output /tmp/ollama.tar.gz \
      "https://github.com/ollama/ollama/archive/refs/tags/v${OLLAMA_VERSION}.tar.gz" \
    && curl --fail --location --retry 5 --retry-all-errors \
      --output /tmp/go.tar.gz \
      "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" \
    && printf '%s  %s\n' "$OLLAMA_SOURCE_SHA256" /tmp/ollama.tar.gz | sha256sum -c - \
    && printf '%s  %s\n' "$GO_SHA256" /tmp/go.tar.gz | sha256sum -c - \
    && tar -xzf /tmp/go.tar.gz -C /usr/local \
    && tar -xzf /tmp/ollama.tar.gz -C /tmp

ENV PATH=/usr/local/go/bin:$PATH
RUN cd "/tmp/ollama-${OLLAMA_VERSION}" \
    && go get \
      github.com/buger/jsonparser@v1.1.2 \
      golang.org/x/crypto@v0.53.0 \
      golang.org/x/image@v0.43.0 \
      golang.org/x/mod@v0.40.0 \
      golang.org/x/net@v0.56.0 \
      golang.org/x/text@v0.39.0 \
    && go mod tidy \
    && CGO_ENABLED=1 go build -trimpath -buildmode=pie \
      -ldflags "-s -w -X github.com/ollama/ollama/version.Version=${OLLAMA_VERSION}-reasonweave.1" \
      -o /out/ollama .

FROM ollama/ollama:0.33.1

COPY infra/ollama/ensure-model.sh /usr/local/bin/reasonweave-ensure-model
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o 10001 -g 10001 /home/ollama/.ollama \
    && chmod 0555 /usr/local/bin/reasonweave-ensure-model
COPY --from=build /out/ollama /usr/bin/ollama
ENV HOME=/home/ollama
USER 10001:10001
