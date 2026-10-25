# The built frontend, served by Caddy. Caddy also passes /api, /auth and /updating on to the
# services (Caddyfile), so the browser only ever talks to one address and no service needs CORS.
FROM node:22-alpine AS build
WORKDIR /app

# dependencies first, so a code change doesn't re-install them
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM caddy:2.11-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
