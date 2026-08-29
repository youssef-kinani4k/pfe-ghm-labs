# Etage 1 — build. package.json et le lock sont copies seuls avant le source, meme raison
# que le pom cote backend : la couche npm ci ne se refait que si les dependances bougent.
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Etage 2 — runtime. angular.json ne declare aucun outputPath : dist/<projet>/browser est
# le defaut d'Angular 20, et le projet s'appelle « frontend ».
FROM nginx:alpine
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
