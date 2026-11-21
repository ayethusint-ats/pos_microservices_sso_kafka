# syntax=docker/dockerfile:1.7
# Phase 15: the Angular SPA. Build with Node, serve the static files with nginx (no Node at runtime).

# Angular 22 needs Node >= 24.15 (found when 24.11 failed the build).
FROM node:24.18.0-alpine AS build
WORKDIR /app
# Dependencies first: this layer is reused until package*.json change.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginxinc/nginx-unprivileged:1.29.3-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/banking-frontend/browser /usr/share/nginx/html
EXPOSE 8080
# 127.0.0.1, not localhost: found live that Alpine resolves localhost to ::1 (IPv6), where nginx
# isn't listening, so the check failed although the site was up.
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -qO /dev/null http://127.0.0.1:8080/healthz || exit 1
