#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# CafeQR 2.0 — Service Status Dashboard
# ──────────────────────────────────────────────────────────────
set -euo pipefail

APP_DIR="/home/cafeqr/app"
COMPOSE_FILE="${APP_DIR}/docker-compose.prod.yml"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║    CafeQR 2.0 — Production Status                       ║"
echo "╚══════════════════════════════════════════════════════════╝"

echo ""
echo "── System Resources ──"
echo "  CPU Cores:  $(nproc)"
echo "  RAM Total:  $(free -h | awk '/Mem:/{print $2}')"
echo "  RAM Used:   $(free -h | awk '/Mem:/{print $3}')"
echo "  RAM Free:   $(free -h | awk '/Mem:/{print $4}')"
echo "  Swap Used:  $(free -h | awk '/Swap:/{print $3}')"
echo "  Disk Usage: $(df -h / | awk 'NR==2{print $3 "/" $2 " (" $5 " used)"}')"
echo "  Uptime:     $(uptime -p)"

echo ""
echo "── Docker Containers ──"
docker compose -f "${COMPOSE_FILE}" ps 2>/dev/null || echo "  Compose stack not running."

echo ""
echo "── Container Resource Usage ──"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}" 2>/dev/null || echo "  No containers running."

echo ""
echo "── Health Checks ──"
for svc in caddy backend db redis; do
  CONTAINER="cafeqr-${svc}"
  if docker inspect "${CONTAINER}" &>/dev/null; then
    STATUS=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "${CONTAINER}" 2>/dev/null)
    STATE=$(docker inspect --format='{{.State.Status}}' "${CONTAINER}" 2>/dev/null)
    UPTIME=$(docker inspect --format='{{.State.StartedAt}}' "${CONTAINER}" 2>/dev/null | cut -d'.' -f1)
    printf "  %-12s state=%-9s health=%-12s started=%s\n" "${svc}:" "${STATE}" "${STATUS}" "${UPTIME}"
  else
    printf "  %-12s NOT FOUND\n" "${svc}:"
  fi
done

echo ""
echo "── SSL Certificate ──"
DOMAIN=$(grep -E '^CADDY_SITE_ADDRESS=' "${APP_DIR}/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"'" || true)
if [ -n "${DOMAIN}" ] && [ "${DOMAIN}" != ":80" ]; then
  EXPIRY=$(echo | openssl s_client -connect "${DOMAIN}:443" -servername "${DOMAIN}" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  echo "  Domain:  ${DOMAIN}"
  echo "  Expires: ${EXPIRY:-Unable to check}"
else
  echo "  IP-first HTTP mode. Set CADDY_SITE_ADDRESS in .env after DNS is ready."
fi

echo ""
echo "── Recent Errors (last 10 lines) ──"
docker compose -f "${COMPOSE_FILE}" logs --tail=10 backend 2>/dev/null | grep -i "error\|exception\|fail" | tail -5 || echo "  No recent errors."
