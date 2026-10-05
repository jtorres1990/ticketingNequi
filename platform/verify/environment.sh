#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Environment, security and reproducibility verifier (PLAT-INC-007, plan 8.1 and 8.4).
#
# Checks a RUNNING default topology ("docker compose up -d --wait") of this project:
#   - Compose resolves with the fictitious .env.example and leaks nothing real;
#   - exactly the 7 default services (9 with profile load); load profile not started;
#   - 6 services healthy, infra-init exited 0, api and worker on the SAME image ID;
#   - host ports = the approved ones, all bound to 127.0.0.1; worker publishes none;
#   - no data volumes (ephemeral emulators); restart "no" everywhere;
#   - own images: non-root user, read-only root, cap_drop ALL, no-new-privileges;
#   - external images pinned by tag AND digest, no ":latest";
#   - the Payment Mock API key and JWTs absent from image history, image config and logs.
# Secrets are read from the env file into variables and NEVER printed.
#
# Usage (repository root): bash platform/verify/environment.sh [--env-file <file>] [-p <project>]
# Exit code = number of failed checks (0 = all pass).
# -----------------------------------------------------------------------------
set -uo pipefail
export MSYS_NO_PATHCONV=1

ENV_FILE=.env
PROJECT=
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE=$2; shift ;;
    -p) PROJECT=$2; shift ;;
  esac
  shift
done
dc() { if [ -n "$PROJECT" ]; then docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; else docker compose --env-file "$ENV_FILE" "$@"; fi; }
[ -n "$PROJECT" ] || PROJECT=$(dc config --format json 2>/dev/null | grep -o -m1 '"name": *"[^"]*"' | sed -E 's/.*"([^"]*)"$/\1/')
[ -n "$PROJECT" ] || PROJECT=ticketing-platform
# env_value <NAME> [default]: value from the env file (CR stripped), else the Compose default.
env_value() { v=$(grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r'); printf '%s' "${v:-${2:-}}"; }

PASSED=0 FAILED=0
pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1 = $3"; else fail "$1: expected [$2] actual [$3]"; fi; }
count() { grep -c -F -- "$1" || true; }

BASE="dynamodb-local infra-init local-idp localstack payment-mock ticketing-api ticketing-worker"
PERSISTENT="dynamodb-local localstack local-idp payment-mock ticketing-api ticketing-worker"
OWN="payment-mock infra-init local-idp ticketing-api ticketing-worker"
KEY=$(env_value PAYMENT_MOCK_API_KEY)
cid() { dc ps -a -q "$1" 2>/dev/null | head -1; }

echo "== Static: Compose model (project $PROJECT)"
example=$(docker compose --env-file .env.example --profile load config 2>&1); rc=$?
check "config with .env.example (profile load) exit" 0 "$rc"
check "eyJ / PRIVATE KEY / AKIA in resolved example config" "0 0 0" \
  "$(printf '%s' "$example" | count eyJ) $(printf '%s' "$example" | count 'PRIVATE KEY') $(printf '%s' "$example" | count AKIA)"
if [ -n "$KEY" ] && [ "$KEY" != "$(grep -E '^PAYMENT_MOCK_API_KEY=' .env.example | cut -d= -f2- | tr -d '\r')" ]; then
  check "real API key in resolved example config" 0 "$(printf '%s' "$example" | count "$KEY")"
fi
check "':latest' in compose file and Dockerfiles" 0 \
  "$(cat docker-compose.yml ticketing/Dockerfile payment-mock/Dockerfile platform/*/Dockerfile | count ':latest')"
external=$(dc --profile load config --images 2>/dev/null | grep -v '^ticketing-platform/' | sort -u)
unpinned=$(printf '%s\n' "$external" | grep -v -E '^[^:@]+:[^:@]+@sha256:[0-9a-f]{64}$' | grep -c . || true)
check "external images in compose without tag+digest" 0 "$unpinned"
from_unpinned=$(grep -h -E '^FROM ' ticketing/Dockerfile payment-mock/Dockerfile platform/*/Dockerfile \
  | awk '{print $2}' | grep -v -E '^[^:@]+:[^:@]+@sha256:[0-9a-f]{64}$' | grep -c . || true)
check "Dockerfile FROM without tag+digest" 0 "$from_unpinned"
check "default services" "$BASE" "$(dc config --services | sort | tr '\n' ' ' | sed 's/ $//')"
check "services with profile load" 9 "$(dc --profile load config --services | grep -c .)"

echo "== Running topology"
for s in $PERSISTENT; do
  check "$s health" healthy "$(docker inspect -f '{{.State.Health.Status}}' "$(cid "$s")" 2>/dev/null)"
done
check "infra-init state/exit" "exited 0" "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$(cid infra-init)" 2>/dev/null)"
check "load profile containers" 0 "$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" \
  --format '{{.Label "com.docker.compose.service"}}' | grep -c '^load-' || true)"
api_img=$(docker inspect -f '{{.Image}}' "$(cid ticketing-api)")
worker_img=$(docker inspect -f '{{.Image}}' "$(cid ticketing-worker)")
check "api and worker share one image ID" "$api_img" "$worker_img"
check "api/worker image = ticketing-platform/ticketing:local" "$api_img" \
  "$(docker image inspect -f '{{.Id}}' ticketing-platform/ticketing:local 2>/dev/null)"

echo "== Ports"
ports() { docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}>{{.HostIp}}:{{.HostPort}} {{end}}{{end}}' "$(cid "$1")" | sed 's/ $//'; }
check "dynamodb-local ports" "8000/tcp>127.0.0.1:$(env_value DYNAMODB_HOST_PORT 8000)" "$(ports dynamodb-local)"
check "localstack ports" "4566/tcp>127.0.0.1:$(env_value LOCALSTACK_HOST_PORT 4566)" "$(ports localstack)"
check "local-idp ports" "9000/tcp>127.0.0.1:$(env_value LOCAL_IDP_HOST_PORT 9000)" "$(ports local-idp)"
check "payment-mock ports" "8090/tcp>127.0.0.1:$(env_value PAYMENT_MOCK_HOST_PORT 8090)" "$(ports payment-mock)"
check "ticketing-api ports" \
  "8080/tcp>127.0.0.1:$(env_value TICKETING_API_HOST_PORT 8080) 8081/tcp>127.0.0.1:$(env_value TICKETING_API_MANAGEMENT_HOST_PORT 8081)" \
  "$(ports ticketing-api)"
check "ticketing-worker ports" "" "$(ports ticketing-worker)"
check "infra-init ports" "" "$(ports infra-init)"
check "host bindings not on 127.0.0.1" 0 "$(for s in $BASE; do ports "$s"; echo; done | tr ' ' '\n' | grep . | grep -v -c '>127\.0\.0\.1:' || true)"

echo "== Ephemeral data and restart policy"
check "project volumes" "" "$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | tr '\n' ' ' | sed 's/ $//')"
for s in $BASE; do
  c=$(cid "$s")
  check "$s volume/bind mounts" "" "$(docker inspect -f '{{range .Mounts}}{{.Type}}:{{.Destination}} {{end}}' "$c" | sed 's/ $//')"
  check "$s restart policy" "no" "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$c")"
done

echo "== Hardening of own images"
for s in $OWN; do
  c=$(cid "$s")
  img=$(docker inspect -f '{{.Config.Image}}' "$c")
  user=$(docker image inspect -f '{{.Config.User}}' "$img")
  case "$user" in ''|0|0:*|root|root:*) fail "$s image user is root or empty [$user]" ;; *) pass "$s image user = $user" ;; esac
  check "$s ReadonlyRootfs CapDrop SecurityOpt" "true [ALL] [no-new-privileges:true]" \
    "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' "$c")"
  if [ "$(docker inspect -f '{{.State.Running}}' "$c")" = true ]; then
    check "$s process uid (PID 1)" 10001 "$(docker exec "$c" sh -c 'awk "/^Uid:/{print \$2}" /proc/1/status' 2>/dev/null | tr -d '\r')"
  fi
done
for s in dynamodb-local localstack; do
  # Not counted: external emulator images keep their own user (localstack = root, documented exception
  # of PLAT-INC-002: SQS only, no Docker socket, loopback-bound port).
  printf 'INFO  %s image user = [%s]\n' "$s" "$(docker image inspect -f '{{.Config.User}}' "$(docker inspect -f '{{.Config.Image}}' "$(cid "$s")")")"
done

echo "== Secrets in images and logs"
for img in ticketing payment-mock local-idp infra-init; do
  ref=ticketing-platform/$img:local
  hist=$(docker history --no-trunc --format '{{.CreatedBy}}' "$ref" 2>/dev/null)
  conf=$(docker image inspect -f '{{json .Config}}' "$ref" 2>/dev/null)
  check "$img history/config: eyJ, PRIVATE KEY, PAYMENT_MOCK_API_KEY=, AWS_SECRET" "0 0 0 0" \
    "$(printf '%s%s' "$hist" "$conf" | count eyJ) $(printf '%s%s' "$hist" "$conf" | count 'PRIVATE KEY') $(printf '%s%s' "$hist" "$conf" | count 'PAYMENT_MOCK_API_KEY=') $(printf '%s%s' "$hist" "$conf" | count 'AWS_SECRET_ACCESS_KEY=')"
  [ -n "$KEY" ] && check "$img history/config: API key value" 0 "$(printf '%s%s' "$hist" "$conf" | count "$KEY")"
done
logs=$(dc logs --no-color 2>&1)
check "logs: eyJ / Bearer / PRIVATE KEY" "0 0 0" \
  "$(printf '%s' "$logs" | count eyJ) $(printf '%s' "$logs" | count 'Bearer ') $(printf '%s' "$logs" | count 'PRIVATE KEY')"
[ -n "$KEY" ] && check "logs: API key value" 0 "$(printf '%s' "$logs" | count "$KEY")"

echo
echo "RESULT: $PASSED passed, $FAILED failed"
exit "$FAILED"
