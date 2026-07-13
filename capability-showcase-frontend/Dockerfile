# 独立前端：多阶段构建（node 构建 → nginx 静态托管）。前后端分离，不依赖任何后端服务。
# 网关地址在构建期烘焙：docker build --build-arg VITE_EDGE_BASE_URL=https://api.example.com
FROM node:20-alpine AS build
ARG VITE_EDGE_BASE_URL=
ARG VITE_BASE=/
ARG VITE_AUTH_BASE_URL=
ARG VITE_REQUIRE_LOGIN=true
# RBAC 控制台三个 kill switch：默认开（只可强制关闭）。真正可用仍受 role-admin scope / 运行时配置约束。
ARG VITE_RBAC_CONSOLE_ENABLED=true
ARG VITE_SHARED_KB_UI_ENABLED=true
# 生产默认关闭 demo 一键登录，且不注入口令 —— 保证产物不含任何明文口令。
ARG VITE_DEMO_LOGIN_ENABLED=false
ARG VITE_DEMO_PASSWORD=
ENV VITE_EDGE_BASE_URL=$VITE_EDGE_BASE_URL
ENV VITE_BASE=$VITE_BASE
ENV VITE_AUTH_BASE_URL=$VITE_AUTH_BASE_URL
ENV VITE_REQUIRE_LOGIN=$VITE_REQUIRE_LOGIN
ENV VITE_RBAC_CONSOLE_ENABLED=$VITE_RBAC_CONSOLE_ENABLED
ENV VITE_SHARED_KB_UI_ENABLED=$VITE_SHARED_KB_UI_ENABLED
ENV VITE_DEMO_LOGIN_ENABLED=$VITE_DEMO_LOGIN_ENABLED
ENV VITE_DEMO_PASSWORD=$VITE_DEMO_PASSWORD
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
