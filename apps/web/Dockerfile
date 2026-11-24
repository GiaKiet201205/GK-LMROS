FROM node:22-alpine AS build
ARG PORTAL=dispatcher
ENV VITE_PORTAL=$PORTAL
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine
ARG PORTAL=dispatcher
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN test "$PORTAL" = dispatcher -o "$PORTAL" = driver -o "$PORTAL" = customer && sed -i "s/__PORTAL__/$PORTAL/g" /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/${PORTAL}/ /usr/share/nginx/html/
