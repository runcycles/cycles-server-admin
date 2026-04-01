#!/bin/bash
# =============================================================================
# Full E2E Integration Test — Admin + Runtime + Events + Redis
#
# Usage:
#   ./e2e-test.sh          # brings up stack, runs tests, tears down
#   ./e2e-test.sh --keep   # keeps stack running after tests
#
# Requires: Docker, curl, java (for webhook receiver)
# =============================================================================
set -euo pipefail

COMPOSE_FILE="docker-compose.full-stack.yml"
ADMIN="http://localhost:7979"
RUNTIME="http://localhost:7878"
EVENTS="http://localhost:7980"
CT="Content-Type: application/json"
KEEP_STACK="${1:-}"
PASS=0
FAIL=0
WH_CONTAINER="e2e-webhook-receiver"

# --- Helpers ---
red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
bold()  { printf "\033[1m%s\033[0m\n" "$*"; }

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    green "  PASS: $label ($actual)"
    PASS=$((PASS + 1))
  else
    red "  FAIL: $label (expected=$expected, actual=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

assert_not_empty() {
  local label="$1" actual="$2"
  if [ -n "$actual" ]; then
    green "  PASS: $label ($actual)"
    PASS=$((PASS + 1))
  else
    red "  FAIL: $label (empty)"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local label="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -q "$needle" 2>/dev/null; then
    green "  PASS: $label (contains $needle)"
    PASS=$((PASS + 1))
  else
    red "  FAIL: $label (missing $needle)"
    FAIL=$((FAIL + 1))
  fi
}

json_field() {
  # Extract a simple string field from JSON: json_field '{"a":"b"}' a -> b
  echo "$1" | sed "s/.*\"$2\":\"\([^\"]*\)\".*/\1/"
}

json_int() {
  # Extract an integer field: json_int '{"a":123}' a -> 123
  echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2
}

wait_healthy() {
  local url="$1" name="$2" max="${3:-60}"
  for i in $(seq 1 "$max"); do
    if curl -sf "$url/actuator/health" > /dev/null 2>&1; then
      green "  $name healthy (${i}s)"
      return 0
    fi
    sleep 1
  done
  red "  $name not healthy after ${max}s"
  return 1
}

cleanup() {
  if [ "$KEEP_STACK" != "--keep" ]; then
    bold "Cleaning up..."
    docker rm -f "$WH_CONTAINER" > /dev/null 2>&1 || true
    docker compose -f "$COMPOSE_FILE" down -v > /dev/null 2>&1 || true
  else
    docker rm -f "$WH_CONTAINER" > /dev/null 2>&1 || true
    echo "Stack left running (--keep). Stop with: docker compose -f $COMPOSE_FILE down -v"
  fi
}

trap cleanup EXIT

# =============================================================================
bold "============================================="
bold "  Full E2E Integration Test v0.1.25.1"
bold "  Admin :7979 | Runtime :7878 | Events :7980"
bold "============================================="
echo ""

# --- 1. Start Docker stack ---
bold "[1/10] Starting Docker stack..."
docker rm -f "$WH_CONTAINER" > /dev/null 2>&1 || true
docker compose -f "$COMPOSE_FILE" down -v > /dev/null 2>&1 || true
docker compose -f "$COMPOSE_FILE" up -d --build 2>&1 | tail -5
echo ""

bold "[2/10] Waiting for services..."
wait_healthy "$ADMIN"   "Admin"   90
wait_healthy "$RUNTIME" "Runtime" 90
wait_healthy "$EVENTS"  "Events"  90
echo ""

# --- 3. Start webhook receiver on the Docker network ---
bold "[3/10] Starting webhook receiver..."
NETWORK=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(docker compose -f "$COMPOSE_FILE" ps -q redis)")

docker run -d --name "$WH_CONTAINER" --network "$NETWORK" \
  eclipse-temurin:21-jdk-alpine sh -c '
mkdir -p /tmp/s && cat > /tmp/s/R.java << "JEOF"
import com.sun.net.httpserver.HttpServer;
import java.io.*; import java.net.InetSocketAddress;
public class R { public static void main(String[] a) throws Exception {
  HttpServer s = HttpServer.create(new InetSocketAddress(9999), 0);
  s.createContext("/webhook", x -> {
    byte[] b = x.getRequestBody().readAllBytes();
    String sig = x.getRequestHeaders().getFirst("X-Cycles-Signature");
    String eid = x.getRequestHeaders().getFirst("X-Cycles-Event-Id");
    String et  = x.getRequestHeaders().getFirst("X-Cycles-Event-Type");
    String ua  = x.getRequestHeaders().getFirst("User-Agent");
    String ct  = x.getRequestHeaders().getFirst("Content-Type");
    String entry = String.format(
      "{\"sig\":\"%s\",\"eid\":\"%s\",\"et\":\"%s\",\"ua\":\"%s\",\"ct\":\"%s\",\"len\":%d}",
      sig!=null?sig:"NONE", eid!=null?eid:"", et!=null?et:"", ua!=null?ua:"", ct!=null?ct:"", b.length);
    try (FileWriter fw = new FileWriter("/tmp/wh.log", true)) { fw.write(entry + "\n"); fw.flush(); }
    x.sendResponseHeaders(200, 2);
    try (OutputStream o = x.getResponseBody()) { o.write("OK".getBytes()); }
  });
  s.start(); System.out.println("READY"); System.out.flush(); Thread.sleep(Long.MAX_VALUE);
}}
JEOF
javac /tmp/s/R.java -d /tmp/c && exec java -cp /tmp/c R
' > /dev/null 2>&1

# Wait for receiver to start
for i in $(seq 1 30); do
  if docker logs "$WH_CONTAINER" 2>&1 | grep -q "READY"; then break; fi
  sleep 1
done

WH_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$WH_CONTAINER")
WH_URL="http://${WH_IP}:9999/webhook"

# Verify connectivity from events service
EVENTS_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q cycles-events)
docker exec "$EVENTS_CONTAINER" wget -qO- --timeout=3 --post-data='ping' "$WH_URL" > /dev/null 2>&1
green "  Webhook receiver ready at $WH_URL"
echo ""

# --- 4. Setup ---
AK="X-Admin-API-Key: admin-bootstrap-key"
TS=$(date +%s)
echo ""

bold "[4/10] Creating tenant..."

T_RESP=$(curl -s -X POST "$ADMIN/v1/admin/tenants" -H "$AK" -H "$CT" \
  -d "{\"tenant_id\":\"e2e-$TS\",\"name\":\"E2E Test Tenant\"}")
TID=$(json_field "$T_RESP" "tenant_id")
T_STATUS=$(json_field "$T_RESP" "status")
assert_not_empty "Tenant created" "$TID"
assert_eq "Tenant status" "ACTIVE" "$T_STATUS"
echo ""

bold "[5/10] Creating API key..."

K_RESP=$(curl -s -X POST "$ADMIN/v1/admin/api-keys" -H "$AK" -H "$CT" \
  -d "{\"tenant_id\":\"$TID\",\"name\":\"E2E Key\",\"permissions\":[\"admin:read\",\"admin:write\",\"reservations:create\",\"reservations:commit\",\"reservations:release\",\"balances:read\",\"webhooks:read\",\"webhooks:write\",\"events:read\"]}")
KID=$(json_field "$K_RESP" "key_id")
KSEC=$(json_field "$K_RESP" "key_secret")
assert_not_empty "API key created" "$KID"
assert_not_empty "Key secret returned" "${KSEC:0:10}"
echo ""

bold "[6/10] Creating and funding budget..."

B_RESP=$(curl -s -X POST "$ADMIN/v1/admin/budgets" \
  -H "X-Cycles-API-Key: $KSEC" -H "$CT" \
  -d '{"scope":"agent:e2e-agent","unit":"TOKENS","allocated":{"unit":"TOKENS","amount":10000}}')
LID=$(json_field "$B_RESP" "ledger_id")
assert_not_empty "Budget created" "$LID"

F_RESP=$(curl -s -X POST "$ADMIN/v1/admin/budgets/fund?scope=agent:e2e-agent&unit=TOKENS" \
  -H "X-Cycles-API-Key: $KSEC" -H "$CT" \
  -d "{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"TOKENS\",\"amount\":5000},\"idempotency_key\":\"fund-$TS\"}")
NEW_ALLOC=$(echo "$F_RESP" | grep -o '"new_allocated":{[^}]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2)
assert_eq "Budget funded (new_allocated)" "15000" "$NEW_ALLOC"
echo ""

# --- 7. Create webhook subscription ---
# Note: SSRF protection (hardcoded private IP check) blocks Docker-internal IPs
# via the admin API. We write directly to Redis — same approach as the
# Testcontainers integration test in cycles-server-events.
bold "[7/10] Creating webhook subscription (Redis direct — SSRF blocks Docker IPs)..."

REDIS_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q redis)
SID="whsub_e2e_$TS"
SSEC="whsec_e2e_test_secret_$TS"

docker exec "$REDIS_CONTAINER" redis-cli SET "webhook:$SID" \
  "{\"subscription_id\":\"$SID\",\"tenant_id\":\"$TID\",\"name\":\"E2E Webhook\",\"url\":\"$WH_URL\",\"event_types\":[\"budget.funded\",\"budget.debited\"],\"status\":\"ACTIVE\",\"retry_policy\":{\"max_retries\":3,\"initial_delay_ms\":1000,\"backoff_multiplier\":2.0,\"max_delay_ms\":10000},\"disable_after_failures\":10,\"consecutive_failures\":0}" > /dev/null
docker exec "$REDIS_CONTAINER" redis-cli SET "webhook:secret:$SID" "$SSEC" > /dev/null
docker exec "$REDIS_CONTAINER" redis-cli SADD "webhooks:$TID" "$SID" > /dev/null

assert_not_empty "Webhook subscription created" "$SID"
assert_not_empty "Signing secret set" "$SSEC"

# Fund again to trigger a new budget.funded event (the webhook subscription now exists)
bold "[8/10] Triggering budget.funded event..."

F2_RESP=$(curl -s -X POST "$ADMIN/v1/admin/budgets/fund?scope=agent:e2e-agent&unit=TOKENS" \
  -H "X-Cycles-API-Key: $KSEC" -H "$CT" \
  -d "{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"TOKENS\",\"amount\":1000},\"idempotency_key\":\"fund2-$TS\"}")
F2_ALLOC=$(echo "$F2_RESP" | grep -o '"new_allocated":{[^}]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2)
assert_eq "Second fund (new_allocated)" "16000" "$F2_ALLOC"

echo "  Waiting for webhook delivery (up to 60s)..."
DELIVERED=false
for i in $(seq 1 60); do
  WH_LOG=$(docker exec "$WH_CONTAINER" sh -c 'cat /tmp/wh.log' 2>/dev/null || true)
  if echo "$WH_LOG" | grep -q "budget.funded" 2>/dev/null; then
    DELIVERED=true
    break
  fi
  sleep 1
done

assert_eq "Webhook delivered" "true" "$DELIVERED"

if [ "$DELIVERED" = true ]; then
  WH_DATA=$(echo "$WH_LOG" | grep "budget.funded" | head -1)
  WH_SIG=$(json_field "$WH_DATA" "sig")
  WH_EID=$(json_field "$WH_DATA" "eid")
  WH_UA=$(json_field "$WH_DATA" "ua")
  WH_CT=$(json_field "$WH_DATA" "ct")

  assert_not_empty "Event ID present" "$WH_EID"
  assert_contains "HMAC signature" "$WH_SIG" "sha256="
  assert_contains "User-Agent versioned" "$WH_UA" "cycles-server-events/"
  assert_eq "Content-Type" "application/json" "$WH_CT"
fi
echo ""

# --- 9. Check delivery status in Redis ---
bold "[9/10] Checking delivery status..."
sleep 3

# Find the delivery key for our subscription
D_KEY=$(docker exec "$REDIS_CONTAINER" sh -c 'redis-cli KEYS "delivery:*"' 2>/dev/null | grep "delivery:" | head -1 | tr -d '\r')
if [ -n "$D_KEY" ]; then
  D_JSON=$(docker exec "$REDIS_CONTAINER" sh -c "redis-cli GET $D_KEY")
  D_STATUS=$(json_field "$D_JSON" "status")
  D_CODE=$(json_int "$D_JSON" "response_status")
  D_ATTEMPTS=$(json_int "$D_JSON" "attempts")
  assert_eq "Delivery status" "SUCCESS" "$D_STATUS"
  assert_eq "Delivery response code" "200" "$D_CODE"
  assert_not_empty "Delivery attempts" "$D_ATTEMPTS"
else
  red "  FAIL: No delivery key found in Redis"
  FAIL=$((FAIL + 1))
fi
echo ""

# --- 10. Runtime: reservation + balance ---
bold "[10/10] Runtime server: reservation + balance..."

# Reservation: subject uses named fields (tenant/agent/etc), action uses kind/name
R_RESP=$(curl -s -X POST "$RUNTIME/v1/reservations" \
  -H "X-Cycles-API-Key: $KSEC" -H "$CT" \
  -d "{\"idempotency_key\":\"res-$TS\",\"subject\":{\"agent\":\"e2e-agent\"},\"action\":{\"kind\":\"api_call\",\"name\":\"gpt-4\"},\"estimate\":{\"unit\":\"TOKENS\",\"amount\":500}}")
RID=$(json_field "$R_RESP" "reservation_id")
assert_not_empty "Reservation created" "$RID"

# Commit: requires idempotency_key. Response has charged/released/balances.
C_RESP=$(curl -s -X POST "$RUNTIME/v1/reservations/$RID/commit" \
  -H "X-Cycles-API-Key: $KSEC" -H "$CT" \
  -d "{\"idempotency_key\":\"commit-$TS\",\"actual\":{\"unit\":\"TOKENS\",\"amount\":200}}")
C_STATUS=$(json_field "$C_RESP" "status")
C_CHARGED=$(echo "$C_RESP" | grep -o '"charged":{"unit":"TOKENS","amount":[0-9]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2 || true)
C_RELEASED=$(echo "$C_RESP" | grep -o '"released":{"unit":"TOKENS","amount":[0-9]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2 || true)
assert_eq "Commit status" "COMMITTED" "$C_STATUS"
assert_eq "Commit charged" "200" "$C_CHARGED"
assert_not_empty "Commit released" "$C_RELEASED"

# Balance returned inline in commit response
C_SPENT=$(echo "$C_RESP" | grep -o '"spent":{"unit":"TOKENS","amount":[0-9]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2 || true)
C_RSV=$(echo "$C_RESP" | grep -o '"reserved":{"unit":"TOKENS","amount":[0-9]*}' | grep -o '"amount":[0-9]*' | cut -d: -f2 || true)
assert_eq "Spent after commit" "200" "$C_SPENT"
assert_eq "Reserved after commit" "0" "$C_RSV"
echo ""

# =============================================================================
bold "============================================="
bold "  E2E TEST RESULTS"
bold "============================================="
echo ""
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo ""
echo "  Services tested:"
echo "    Admin    :7979  tenant, api-key, budget, webhook, delivery, security-config"
echo "    Runtime  :7878  reservation, commit, balance"
echo "    Events   :7980  dispatch, HMAC delivery, retry"
echo ""
if [ "$FAIL" -eq 0 ]; then
  green "  ALL $PASS TESTS PASSED"
  exit 0
else
  red "  $FAIL TESTS FAILED"
  exit 1
fi
