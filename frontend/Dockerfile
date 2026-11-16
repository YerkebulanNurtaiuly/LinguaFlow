# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM node:26-alpine AS build
WORKDIR /app

# Install dependencies first so they are cached until the lockfile changes
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build
# /version.txt names the deployed commit, so a deploy can be checked from outside (OPS-03)
ARG APP_VERSION=dev
RUN echo "${APP_VERSION}" > dist/version.txt

# ---- Runtime stage (OPS-01): static files behind nginx, which also proxies /api ----
# nginx-unprivileged runs as a non-root user (uid 101) and listens on 8080
FROM nginxinc/nginx-unprivileged:1.31-alpine
# The commit the image was built from (OPS-03); "dev" for local builds
ARG APP_VERSION=dev
LABEL org.opencontainers.image.title="LinguaFlow frontend" \
      org.opencontainers.image.source="https://github.com/YerkebulanNurtaiuly/LinguaFlow" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.revision="${APP_VERSION}"
# Where /api is proxied to; the compose service by default. A platform that names its
# services differently sets BACKEND_URL, with or without http:// (Render's fromService
# gives "linguaflow-api-xyz:8080"). docker/10-backend-url.envsh adds the scheme and finds
# the DNS resolver nginx needs to look the backend up per request (OPS-02).
ENV BACKEND_URL=http://backend:8080 \
    NGINX_ENVSUBST_FILTER=^(BACKEND_URL|NGINX_LOCAL_RESOLVERS)$
COPY --chmod=0755 docker/10-backend-url.envsh /docker-entrypoint.d/10-backend-url.envsh
# Rendered to /etc/nginx/conf.d/default.conf at startup with those two filled in; the
# filter leaves nginx's own variables like $host alone
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
