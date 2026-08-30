# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
ARG RW_IMAGE_REVISION=unknown
ARG RW_IMAGE_VERSION=0.4.1
LABEL org.opencontainers.image.title="ReasonWeave backend" \
      org.opencontainers.image.description="API-first evidence reasoning engine" \
      org.opencontainers.image.source="https://github.com/Chen-AnJu/ReasonWeave" \
      org.opencontainers.image.revision="${RW_IMAGE_REVISION}" \
      org.opencontainers.image.version="${RW_IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -S -g 10001 reasonweave \
    && adduser -S -D -H -u 10001 -G reasonweave reasonweave
WORKDIR /app
COPY --from=build /workspace/target/reasonweave-backend-0.4.1.jar /app/app.jar
RUN mkdir -p /data/blob && chown -R reasonweave:reasonweave /app /data
USER reasonweave
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
