FROM postgres:16-alpine3.24 AS build

ARG PGVECTOR_VERSION=0.8.6
ARG PGVECTOR_SHA256=10bf9938906e5d643bbc4a7eea104b6f57ba4898e5b76b20e60484ea1d5a7f8f

RUN apk add --no-cache build-base ca-certificates curl \
    && curl --fail --location --retry 5 --retry-all-errors \
      --output /tmp/pgvector.tar.gz \
      "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" \
    && printf '%s  %s\n' "$PGVECTOR_SHA256" /tmp/pgvector.tar.gz | sha256sum -c - \
    && tar -xzf /tmp/pgvector.tar.gz -C /tmp \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" OPTFLAGS="" with_llvm=no \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" install with_llvm=no

FROM postgres:16-alpine3.24

ARG RW_IMAGE_REVISION=unknown
ARG RW_IMAGE_VERSION=0.4.1
LABEL org.opencontainers.image.title="ReasonWeave PostgreSQL" \
      org.opencontainers.image.description="PostgreSQL 16 with pgvector for ReasonWeave" \
      org.opencontainers.image.source="https://github.com/Chen-AnJu/ReasonWeave" \
      org.opencontainers.image.revision="${RW_IMAGE_REVISION}" \
      org.opencontainers.image.version="${RW_IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apk upgrade --no-cache \
    && rm -f /usr/local/bin/gosu
COPY --from=build /usr/local/lib/postgresql/vector.so /usr/local/lib/postgresql/vector.so
COPY --from=build /usr/local/share/postgresql/extension/vector* /usr/local/share/postgresql/extension/
USER postgres
