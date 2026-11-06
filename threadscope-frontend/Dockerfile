# ── Stage 1: Build ──
FROM node:20-alpine AS builder
WORKDIR /app

ARG NPM_REGISTRY=https://registry.npmjs.org
COPY threadscope-frontend/package.json threadscope-frontend/package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}

COPY threadscope-frontend/ ./
# vite 按前端目录的上一级读取这份手册（容器内即 /docs）
COPY docs/jfr-advanced-manual.html /docs/jfr-advanced-manual.html
RUN npm run build

# ── Stage 2: Serve ──
FROM nginx:stable-alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY threadscope-frontend/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
