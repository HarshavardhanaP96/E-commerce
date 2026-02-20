# Build Stage
FROM node:20-alpine as builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Production Stage
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --only=production
COPY --from=builder /app/dist ./dist
# Ensure uploads directory exists if used
RUN mkdir -p uploads
EXPOSE 3000
CMD ["node", "dist/app.js"]
