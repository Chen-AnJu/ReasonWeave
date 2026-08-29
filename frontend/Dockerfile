FROM node:22-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY frontend/package.json frontend/package.json
COPY tools/domain-pack-cli/package.json tools/domain-pack-cli/package.json
COPY tools/evidence-cli/package.json tools/evidence-cli/package.json
RUN pnpm install --frozen-lockfile --filter @reasonweave/frontend...
COPY contracts ./contracts
COPY frontend ./frontend
RUN pnpm --dir frontend build

FROM nginx:1.31.4-alpine3.24 AS runtime
COPY --chown=nginx:nginx frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
RUN apk upgrade --no-cache \
    && touch /var/run/nginx.pid \
    && chown -R nginx:nginx /var/cache/nginx /var/run/nginx.pid
USER nginx
EXPOSE 8080
