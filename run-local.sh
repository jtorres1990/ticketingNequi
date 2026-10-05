#!/usr/bin/env sh
# Runs the ticketing api and worker roles as local JVM processes against the
# Docker Compose environment (DynamoDB Local, LocalStack, local-idp, payment-mock).
#
# Usage (from the repository root, Git Bash / Linux / macOS):
#   ./run-local.sh start    # compose up of the dependencies + build jar if missing + start api and worker
#   ./run-local.sh smoke    # create an Event, buy a Ticket and wait for CONFIRMED
#   ./run-local.sh stop     # stop api and worker (compose stays up)
#   ./run-local.sh down     # stop api and worker and remove the compose environment
#
# Requires JDK 25 (JAVA_HOME or java on PATH), Docker, curl and a .env copied
# from .env.example.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
RUN_DIR="$ROOT/.run"
JAR="$ROOT/ticketing/bootstrap/target/ticketing-bootstrap-0.1.0-SNAPSHOT.jar"
DEPENDENCIES="dynamodb-local localstack infra-init local-idp payment-mock"

[ -f "$ROOT/.env" ] || { echo "Missing .env: cp .env.example .env and adjust it" >&2; exit 1; }
set -a; . "$ROOT/.env"; set +a

JAVA=java
[ -n "${JAVA_HOME:-}" ] && JAVA="$JAVA_HOME/bin/java"

API_URL="http://localhost:${API_PORT:-8080}"
IDP_URL="http://localhost:${LOCAL_IDP_HOST_PORT:-9000}"
SQS_URL="http://localhost:${LOCALSTACK_HOST_PORT:-4566}"
QUEUE_BASE="$SQS_URL/queue/${AWS_REGION}/000000000000"

common_env() {
  export TICKETING_DYNAMODB_TABLE="$TICKETING_TABLE_NAME"
  export TICKETING_DYNAMODB_ENDPOINT="http://localhost:${DYNAMODB_HOST_PORT:-8000}"
  export TICKETING_SQS_ENDPOINT="$SQS_URL"
  export TICKETING_SQS_ORDERS_QUEUE_URL="$QUEUE_BASE/$ORDERS_QUEUE_NAME"
  export TICKETING_SQS_PROVISIONING_QUEUE_URL="$QUEUE_BASE/$PROVISIONING_QUEUE_NAME"
  export TICKETING_LOG_FORMAT=""
}

start_role() {
  role=$1 port=$2 mgmt=$3
  (
    common_env
    export TICKETING_ROLE="$role" TICKETING_SERVER_PORT="$port" TICKETING_MANAGEMENT_PORT="$mgmt"
    if [ "$role" = api ]; then
      # The issuer must equal the token "iss" (fixed by local-idp); JWKS is fetched from the host port.
      export TICKETING_SECURITY_ISSUER="$LOCAL_IDP_ISSUER"
      export TICKETING_SECURITY_JWK_SET_URI="$IDP_URL/.well-known/jwks.json"
      export TICKETING_SECURITY_ALLOWED_CLIENT_IDS="$LOCAL_IDP_CLIENT_ID"
    else
      export TICKETING_PAYMENT_BASE_URL="http://localhost:${PAYMENT_MOCK_HOST_PORT:-8090}"
      export TICKETING_PAYMENT_API_KEY="$PAYMENT_MOCK_API_KEY"
      export TICKETING_WORKER_ID="local-worker-1"
    fi
    nohup "$JAVA" -jar "$JAR" > "$RUN_DIR/$role.log" 2>&1 &
    echo $! > "$RUN_DIR/$role.pid"
  )
  i=0
  until curl -fs "http://localhost:$port/readyz" > /dev/null 2>&1; do
    i=$((i + 1))
    if [ $i -gt 60 ]; then echo "$role did not become ready; see $RUN_DIR/$role.log" >&2; exit 1; fi
    sleep 1
  done
  echo "$role ready on :$port (management :$mgmt, log $RUN_DIR/$role.log)"
}

stop_roles() {
  for role in api worker; do
    if [ -f "$RUN_DIR/$role.pid" ]; then
      kill "$(cat "$RUN_DIR/$role.pid")" 2> /dev/null || true
      rm -f "$RUN_DIR/$role.pid"
      echo "$role stopped"
    fi
  done
}

token() {
  curl -fs -X POST "$IDP_URL/token" -d "identity=$1" | sed -E 's/.*"access_token" *: *"([^"]+)".*/\1/'
}

json_field() {
  sed -E "s/.*\"$1\" *: *\"([^\"]+)\".*/\1/"
}

smoke() {
  admin=$(token admin)
  customer=$(token customer-a)
  starts=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -v+30d +%Y-%m-%dT%H:%M:%SZ)
  key="smoke-event-$(date +%s)"
  echo "1) Create Event (ADMIN)"
  event=$(curl -sS -X POST "$API_URL/api/v1/events" \
    -H "Authorization: Bearer $admin" -H "Content-Type: application/json" -H "Idempotency-Key: $key" \
    -d "{\"name\":\"Smoke concert\",\"venue\":\"Local arena\",\"startsAt\":\"$starts\",\"capacity\":10,\"inventory\":{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\",\"seats\":10}]}]}}")
  echo "   $event"
  event_id=$(echo "$event" | json_field eventId)
  echo "2) Wait until ENABLED"
  i=0
  until curl -fs "$API_URL/api/v1/events/$event_id/provisioning" -H "Authorization: Bearer $admin" | grep -q '"ENABLED"'; do
    i=$((i + 1)); [ $i -gt 60 ] && { echo "Event not ENABLED" >&2; exit 1; }; sleep 1
  done
  echo "3) Buy ticket A-1-1 (CUSTOMER)"
  order=$(curl -sS -X POST "$API_URL/api/v1/orders" \
    -H "Authorization: Bearer $customer" -H "Content-Type: application/json" -H "Idempotency-Key: smoke-purchase-$(date +%s)" \
    -d "{\"eventId\":\"$event_id\",\"ticketIds\":[\"A-1-1\"]}")
  echo "   $order"
  order_id=$(echo "$order" | json_field orderId)
  echo "4) Wait for final Order status"
  i=0
  while :; do
    body=$(curl -fs "$API_URL/api/v1/orders/$order_id" -H "Authorization: Bearer $customer")
    status=$(echo "$body" | json_field status)
    case "$status" in CONFIRMED|FAILED|EXPIRED) break ;; esac
    i=$((i + 1)); [ $i -gt 60 ] && break; sleep 1
  done
  echo "   $body"
  [ "$status" = CONFIRMED ] && echo "SMOKE OK: Order $order_id CONFIRMED" || { echo "SMOKE FAILED: status $status" >&2; exit 1; }
}

case "${1:-}" in
  start)
    mkdir -p "$RUN_DIR"
    # Only the dependencies: api and worker run here as local JVMs, never as Compose services (PLAT-IV-015).
    docker compose -f "$ROOT/docker-compose.yml" --env-file "$ROOT/.env" up -d --wait $DEPENDENCIES
    # up --wait does not wait for the one-shot infra-init; the table and queues must exist before the JVMs start.
    i=0
    while :; do
      state=$(docker compose -f "$ROOT/docker-compose.yml" --env-file "$ROOT/.env" ps -a --format '{{.State}} {{.ExitCode}}' infra-init)
      case "$state" in
        "exited 0") break ;;
        exited*) echo "infra-init failed ($state); see: docker compose logs infra-init" >&2; exit 1 ;;
      esac
      i=$((i + 1)); [ $i -gt 120 ] && { echo "infra-init did not finish" >&2; exit 1; }
      sleep 1
    done
    if [ ! -f "$JAR" ]; then (cd "$ROOT/ticketing" && sh ./mvnw -B -q -DskipTests package); fi
    stop_roles
    start_role api "${API_PORT:-8080}" "${API_MANAGEMENT_PORT:-8081}"
    start_role worker "${WORKER_PORT:-8082}" "${WORKER_MANAGEMENT_PORT:-8083}"
    ;;
  smoke) smoke ;;
  stop) stop_roles ;;
  down) stop_roles; docker compose -f "$ROOT/docker-compose.yml" --env-file "$ROOT/.env" down -v ;;
  *) echo "usage: $0 start|smoke|stop|down" >&2; exit 2 ;;
esac
