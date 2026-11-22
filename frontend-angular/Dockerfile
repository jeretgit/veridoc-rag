# ---- Stage 1: Build Angular app ----
FROM node:22-alpine AS builder

WORKDIR /build

COPY package.json package-lock.json* ./
RUN npm ci || npm install

COPY . .
RUN npm run build

# ---- Stage 2: Nginx serve static files ----
FROM nginx:alpine

# Remove default nginx welcome page and config
RUN rm -rf /usr/share/nginx/html/* /etc/nginx/conf.d/default.conf

# Copy our nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy Angular build output (the browser/ subdir contains index.html)
COPY --from=builder /build/dist/frontend-angular/browser/ /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
