FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ARG VITE_TJ_API_BASE_URL=""
ARG VITE_TJ_DEV_MODE="false"
ENV VITE_TJ_API_BASE_URL=${VITE_TJ_API_BASE_URL}
ENV VITE_TJ_DEV_MODE=${VITE_TJ_DEV_MODE}
RUN npm run build

FROM caddy:2.10-alpine
COPY frontend/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
USER caddy
EXPOSE 8080
