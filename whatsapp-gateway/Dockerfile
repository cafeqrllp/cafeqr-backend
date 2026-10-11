FROM node:20-alpine

WORKDIR /app

# Install git and tools required by Baileys npm dependencies
RUN apk add --no-cache git python3 make g++

COPY package*.json ./
# The lockfile pins libsignal via git+ssh; the build has no SSH key, so fetch it over HTTPS.
RUN git config --global url."https://github.com/".insteadOf "ssh://git@github.com/" && npm ci --omit=dev

COPY server.js ./

# Session persistence
RUN mkdir -p /app/auth_sessions

EXPOSE 3005

VOLUME ["/app/auth_sessions"]

CMD ["node", "server.js"]
