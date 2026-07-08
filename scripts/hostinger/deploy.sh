#!/usr/bin/env bash
# CafeQR 2.0 - Hostinger production deploy script.
#
# Usage:
#   ./deploy.sh              # Deploy all services
#   ./deploy.sh backend      # Deploy backend after dependencies are healthy
#   ./deploy.sh frontend     # Deploy frontend and ensure Caddy is running
#
# Set PULL_REPO=false when the caller already performed git pull --ff-only.

set -euo pipefail

APP_DIR="${APP_DIR:-/home/cafeqr/app}"
COMPOSE_FILE="${COMPOSE_FILE:-${APP_DIR}/docker-compose.prod.yml}"
SERVICE="${1:-all}"
PULL_REPO="${PULL_REPO:-true}"
COMPOSE=(docker compose -f "${COMPOSE_FILE}")
TMP_FILES=()
BACKEND_WAIT_ATTEMPTS="${BACKEND_WAIT_ATTEMPTS:-120}"
BACKEND_WAIT_DELAY="${BACKEND_WAIT_DELAY:-5}"
SERVICE_LOG_TAIL="${SERVICE_LOG_TAIL:-500}"

cleanup() {
  for file in "${TMP_FILES[@]}"; do
    [ -n "${file}" ] && [ -f "${file}" ] && rm -f "${file}"
  done
}
trap cleanup EXIT

die() {
  echo "ERROR: $*" >&2
  exit 1
}

usage() {
  echo "Usage: $0 [backend|frontend|all]" >&2
}

require_files() {
  [ -d "${APP_DIR}" ] || die "App directory not found: ${APP_DIR}"
  [ -f "${COMPOSE_FILE}" ] || die "Compose file not found: ${COMPOSE_FILE}"
  [ -f "${APP_DIR}/.env" ] || die "Missing ${APP_DIR}/.env. Create it from .env.production.example before deploying."
  [ -f "${APP_DIR}/.env.frontend" ] || die "Missing ${APP_DIR}/.env.frontend. Create it from .env.frontend.example before deploying."
}

update_repo() {
  if [ "${PULL_REPO}" != "true" ]; then
    return
  fi

  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    die "${APP_DIR} is not a git checkout. Clone cafeqr-backend into this directory first."
  fi

  echo "-- Git status before pull --"
  git status --short

  if [ -n "$(git status --porcelain)" ]; then
    die "The VPS checkout has local changes. Move server-only edits into ignored .env/.env.frontend files, commit intentional tracked edits, or clean the checkout before deploying."
  fi

  git pull --ff-only
}

validate_compose() {
  local log_file
  log_file="$(mktemp)"
  TMP_FILES+=("${log_file}")

  echo "-- Validating Docker Compose config --"
  if ! "${COMPOSE[@]}" config -q >"${log_file}" 2>&1; then
    sed -n '1,120p' "${log_file}" >&2
    die "Docker Compose config validation failed."
  fi

  if grep -qi 'variable is not set' "${log_file}"; then
    grep -i 'variable is not set' "${log_file}" >&2 || true
    die "Docker Compose saw an unset variable. If a secret contains $, single-quote it in .env, for example DB_PASSWORD='abc$def'."
  fi
}

container_state() {
  local container="$1"
  docker inspect --format='{{.State.Status}}' "${container}" 2>/dev/null || echo "missing"
}

container_health() {
  local container="$1"
  docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "${container}" 2>/dev/null || echo "missing"
}

print_failure_details() {
  local service="$1"
  local container="cafeqr-${service}"

  echo ""
  echo "-- ${service} container details --"
  docker inspect --format='name={{.Name}} state={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}} started={{.State.StartedAt}} finished={{.State.FinishedAt}}' "${container}" 2>/dev/null || true

  echo ""
  echo "-- ${service} health log --"
  docker inspect --format='{{if .State.Health}}{{range .State.Health.Log}}{{println .End "exit=" .ExitCode}}{{println .Output}}{{end}}{{else}}no healthcheck{{end}}' "${container}" 2>/dev/null || true

  echo ""
  echo "-- ${service} recent logs --"
  docker logs --tail="${SERVICE_LOG_TAIL}" "${container}" 2>/dev/null || true
}

wait_for_service() {
  local service="$1"
  local attempts="${2:-30}"
  local delay="${3:-5}"
  local container="cafeqr-${service}"
  local state
  local health

  echo "-- Waiting for ${service} --"
  for i in $(seq 1 "${attempts}"); do
    state="$(container_state "${container}")"
    health="$(container_health "${container}")"

    if [ "${health}" = "healthy" ]; then
      echo "  ${service}: healthy"
      return 0
    fi

    if [ "${health}" = "no-healthcheck" ] && [ "${state}" = "running" ]; then
      echo "  ${service}: running"
      return 0
    fi

    # Don't abort on "unhealthy" — the container may restart and recover.
    # Only report it and keep waiting.
    if [ "${health}" = "unhealthy" ]; then
      echo "  attempt ${i}/${attempts}: state=${state} health=${health} (waiting for restart...)"
    else
      echo "  attempt ${i}/${attempts}: state=${state} health=${health}"
    fi
    sleep "${delay}"
  done

  print_failure_details "${service}"
  die "${service} did not become ready in $((attempts * delay)) seconds."
}

show_status() {
  echo ""
  echo "-- Service status --"
  "${COMPOSE[@]}" ps || true

  echo ""
  echo "-- Health summary --"
  for service in caddy backend frontend db redis; do
    local container="cafeqr-${service}"
    printf "  %-10s state=%-10s health=%s\n" "${service}" "$(container_state "${container}")" "$(container_health "${container}")"
  done
}

deploy_dependencies() {
  echo "-- Starting database/cache/queue dependencies --"
  "${COMPOSE[@]}" up -d db redis
  wait_for_service db 30 5
  wait_for_service redis 20 3
}

deploy_backend() {
  deploy_dependencies
  echo "-- Pulling and restarting backend --"
  "${COMPOSE[@]}" pull backend
  "${COMPOSE[@]}" up -d --no-deps backend
  wait_for_service backend "${BACKEND_WAIT_ATTEMPTS}" "${BACKEND_WAIT_DELAY}"
}

deploy_frontend() {
  echo "-- Pulling and restarting frontend --"
  "${COMPOSE[@]}" pull frontend
  "${COMPOSE[@]}" up -d --no-deps frontend
  wait_for_service frontend 30 5

  echo "-- Ensuring Caddy is running --"
  "${COMPOSE[@]}" up -d caddy
  wait_for_service caddy 12 5
}

deploy_all() {
  deploy_dependencies
  echo "-- Pulling application images --"
  "${COMPOSE[@]}" pull backend frontend

  echo "-- Restarting application stack --"
  "${COMPOSE[@]}" up -d --remove-orphans backend frontend caddy
  wait_for_service backend "${BACKEND_WAIT_ATTEMPTS}" "${BACKEND_WAIT_DELAY}"
  wait_for_service frontend 30 5
  wait_for_service caddy 12 5
}

case "${SERVICE}" in
  backend|frontend|all)
    ;;
  *)
    usage
    die "Unknown service: ${SERVICE}"
    ;;
esac

cd "${APP_DIR}"

echo "CafeQR 2.0 production deploy"
echo "Service: ${SERVICE}"
echo "App dir: ${APP_DIR}"
echo ""

require_files
update_repo
validate_compose

case "${SERVICE}" in
  backend)
    deploy_backend
    ;;
  frontend)
    deploy_frontend
    ;;
  all)
    deploy_all
    ;;
esac

show_status

echo ""
echo "Deploy complete."
