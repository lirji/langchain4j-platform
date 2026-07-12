# 独立前端：多阶段构建（node 构建 → nginx 静态托管）。前后端分离，不依赖任何后端服务。
# 网关地址在构建期烘焙：docker build --build-arg VITE_EDGE_BASE_URL=https://api.example.com
FROM node:20-alpine AS build
ARG VITE_EDGE_BASE_URL=
ARG VITE_BASE=/
ENV VITE_EDGE_BASE_URL=$VITE_EDGE_BASE_URL
ENV VITE_BASE=$VITE_BASE
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
