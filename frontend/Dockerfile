# Multi-stage build: compile the Vite app, serve the static output with Nginx.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# ci, not install: install is free to update the lockfile, so the image could
# end up on dependency versions no one committed or tested.
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# wget comes from busybox in this image, so no extra layer is needed.
#
# 127.0.0.1, not localhost: busybox wget resolves localhost to [::1] and does
# not fall back to IPv4, while this nginx only has `listen 80` (IPv4). The
# container reported unhealthy for that reason alone while serving the public
# site perfectly. The backend's probe uses curl, which does fall back, so the
# same latent mismatch never showed there.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
