#!/usr/bin/env bash
set -euo pipefail

GATEWAY_BASE_URL="${GATEWAY_BASE_URL:-http://localhost:8080}"
GATESHIELD_ADMIN_TOKEN="${GATESHIELD_ADMIN_TOKEN:-change-me}"

ADMIN_HEADER=(-H "X-Admin-Token: ${GATESHIELD_ADMIN_TOKEN}")
TENANT_ID="smoke-$(date +%s)"
API_KEY="gs_smoke_$(date +%s)_${RANDOM}"

echo "Checking actuator health..."
curl -fsS "${GATEWAY_BASE_URL}/actuator/health" >/dev/null

echo "Checking admin health..."
curl -fsS "${GATEWAY_BASE_URL}/admin/health" >/dev/null

echo "Creating tenant ${TENANT_ID}..."
curl -fsS -X POST "${GATEWAY_BASE_URL}/admin/tenants" \
  "${ADMIN_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"${TENANT_ID}\",\"name\":\"Smoke Tenant\",\"apiKey\":\"${API_KEY}\",\"planName\":\"smoke\",\"enabled\":true}" >/dev/null

echo "Creating or updating route mock-api..."
ROUTE_BODY='{"routeId":"mock-api","pathPattern":"/api/v1/**","targetUrl":"http://mock-backend:8081","allowedMethods":["GET"],"enabled":true,"rateLimitRequests":3,"rateLimitWindowSeconds":60}'
curl -fsS -X POST "${GATEWAY_BASE_URL}/admin/routes" \
  "${ADMIN_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d "${ROUTE_BODY}" >/dev/null || \
curl -fsS -X PUT "${GATEWAY_BASE_URL}/admin/routes/mock-api" \
  "${ADMIN_HEADER[@]}" \
  -H "Content-Type: application/json" \
  -d "${ROUTE_BODY}" >/dev/null

echo "Verifying unauthorized request is blocked..."
status="$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_BASE_URL}/api/v1/hello")"
test "${status}" = "401"

echo "Verifying proxied request succeeds..."
curl -fsS "${GATEWAY_BASE_URL}/api/v1/hello" -H "X-API-Key: ${API_KEY}" >/dev/null

echo "Verifying rate limit eventually returns 429..."
saw_429=false
for _ in $(seq 1 10); do
  status="$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_BASE_URL}/api/v1/hello" -H "X-API-Key: ${API_KEY}")"
  if [ "${status}" = "429" ]; then
    saw_429=true
    break
  fi
done

test "${saw_429}" = "true"
echo "GateShield smoke test passed."
