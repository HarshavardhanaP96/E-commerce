# syntax=docker/dockerfile:1.7
# ---- build ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# ---- runtime: unprivileged nginx (listens on 8080, runs as uid 101) ----
FROM nginxinc/nginx-unprivileged:1.27-alpine
# BACKEND_UPSTREAM is substituted into the template at container start.
ENV BACKEND_UPSTREAM=http://backend:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
