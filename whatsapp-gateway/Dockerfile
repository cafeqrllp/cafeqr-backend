FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY server.js waha_client.js ./

ENV WAHA_MULTI_SESSION=true
EXPOSE 3005

CMD ["node", "server.js"]
