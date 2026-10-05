#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# infra-init negative battery (PLAT-INC-002, plan section 8.2, PLAT-IV-005 a).
#
# Runs in an ISOLATED Compose project (ticketing-platform-negtest) with its own
# host ports, so the main project ticketing-platform is never touched. For each
# case it pre-creates an incompatible resource, runs infra-init and checks that:
#   - infra-init exits with the documented code and a precise message,
#   - the incompatible resource was neither modified nor deleted.
# Finally it removes ONLY the negtest project (containers, network).
#
# Usage (from the repository root, Docker running, infra-init image built):
#   bash platform/verify/infra-init-negative.sh [--env-file <file>]
# -----------------------------------------------------------------------------
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep container paths untouched

ENV_FILE=.env
[ "${1:-}" = "--env-file" ] && ENV_FILE=$2
PROJECT=ticketing-platform-negtest
export DYNAMODB_HOST_PORT=${NEG_DYNAMODB_HOST_PORT:-18000}
export LOCALSTACK_HOST_PORT=${NEG_LOCALSTACK_HOST_PORT:-14566}

dc() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
aws_in() { dc run --rm --no-deps -T --entrypoint sh infra-init -c "$1" 2>/dev/null; }

PASSED=0 FAILED=0
pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }

fresh() {
  dc down --remove-orphans -t 2 >/dev/null 2>&1
  dc up -d --wait dynamodb-local localstack >/dev/null 2>&1 || { echo "cannot start emulators"; exit 99; }
}

# run_init <case> <expected exit> <expected message fragment> [extra env...]
run_init() {
  local name=$1 code=$2 fragment=$3; shift 3
  local args=() e
  for e in "$@"; do args+=(-e "$e"); done
  local out rc
  out=$(dc run --rm --no-deps -T "${args[@]}" infra-init 2>&1); rc=$?
  if [ "$rc" -eq "$code" ] && printf '%s' "$out" | grep -qF -- "$fragment"; then
    pass "$name: exit $rc and message contains [$fragment]"
  else
    fail "$name: expected exit $code with [$fragment]; got exit $rc"
    printf '%s\n' "$out" | tail -8 | sed 's/^/      /'
  fi
  printf '%s\n' "$out" | grep -E '^(INCOMPATIBLE|  expected|  actual|.*ERROR)' | sed 's/^/      | /'
}

expect_equal() { # expect_equal <description> <expected> <actual>
  if [ "$2" = "$3" ]; then pass "$1 = $3"; else fail "$1: expected [$2] actual [$3]"; fi
}

DDB='aws --endpoint-url http://dynamodb-local:8000 dynamodb'
SQS='aws --endpoint-url http://localstack:4566 sqs'
TABLE_JSON=/opt/infra-init/resources/table.json

echo "== case 1: GSI2 projection KEYS_ONLY instead of INCLUDE"
fresh
aws_in "jq '(.GlobalSecondaryIndexes[] | select(.IndexName==\"GSI2\") | .Projection) = {\"ProjectionType\":\"KEYS_ONLY\"}' $TABLE_JSON > /tmp/t.json \
  && $DDB create-table --table-name ticketing --cli-input-json file:///tmp/t.json >/dev/null" || fail "case 1 setup"
run_init "case 1" 1 "INDEX GSI2 GSI2PK:HASH,GSI2SK:RANGE KEYS_ONLY -"
expect_equal "case 1 GSI2 projection untouched" "KEYS_ONLY" \
  "$(aws_in "$DDB describe-table --table-name ticketing --output text --query \"Table.GlobalSecondaryIndexes[?IndexName=='GSI2'].Projection.ProjectionType | [0]\"" | tr -d '\r')"
expect_equal "case 1 no queue created after the failure" "" \
  "$(aws_in "$SQS list-queues --output text --query 'QueueUrls'" | tr -d '\r' | grep -v '^None$')"

echo "== case 2: additional index GSI5"
fresh
aws_in "jq '.AttributeDefinitions += [{\"AttributeName\":\"GSI5PK\",\"AttributeType\":\"S\"}] | .GlobalSecondaryIndexes += [{\"IndexName\":\"GSI5\",\"KeySchema\":[{\"AttributeName\":\"GSI5PK\",\"KeyType\":\"HASH\"}],\"Projection\":{\"ProjectionType\":\"KEYS_ONLY\"}}]' $TABLE_JSON > /tmp/t.json \
  && $DDB create-table --table-name ticketing --cli-input-json file:///tmp/t.json >/dev/null" || fail "case 2 setup"
run_init "case 2" 1 "actual:   INDEX GSI5"
expect_equal "case 2 index set untouched" "GSI1 GSI2 GSI3 GSI4 GSI5" \
  "$(aws_in "$DDB describe-table --table-name ticketing --output text --query 'sort(Table.GlobalSecondaryIndexes[].IndexName)'" | tr -d '\r' | tr '\t' ' ')"

echo "== case 3: TTL enabled on another attribute"
fresh
aws_in "$DDB create-table --table-name ticketing --cli-input-json file://$TABLE_JSON >/dev/null \
  && $DDB update-time-to-live --table-name ticketing --time-to-live-specification Enabled=true,AttributeName=expiresAt >/dev/null" || fail "case 3 setup"
run_init "case 3" 1 "actual:   TTL ENABLED expiresAt"
expect_equal "case 3 TTL untouched" "ENABLED expiresAt" \
  "$(aws_in "$DDB describe-time-to-live --table-name ticketing --output text --query 'TimeToLiveDescription.[TimeToLiveStatus,AttributeName]'" | tr -d '\r' | tr '\t' ' ')"

echo "== case 4: ticketing-orders with VisibilityTimeout 30 and no redrive"
fresh
aws_in "$SQS create-queue --queue-name ticketing-orders --attributes VisibilityTimeout=30 >/dev/null" || fail "case 4 setup"
run_init "case 4" 1 "actual:   VisibilityTimeout=30"
expect_equal "case 4 queue attributes untouched" "30 None" \
  "$(aws_in "$SQS get-queue-attributes --queue-url http://localstack:4566/queue/us-east-1/000000000000/ticketing-orders --attribute-names All --output text --query 'Attributes.[VisibilityTimeout,RedrivePolicy]'" | tr -d '\r' | tr '\t' ' ')"

echo "== case 5: physical table name parameter (TICKETING_TABLE_NAME=ticketing-alt) on a clean emulator"
fresh
run_init "case 5" 0 "table ticketing-alt matches data model v2" TICKETING_TABLE_NAME=ticketing-alt

echo "== case 6: DynamoDB Local absent (bounded wait, 5 s)"
fresh
dc stop -t 2 dynamodb-local >/dev/null 2>&1
start=$(date +%s)
run_init "case 6" 3 "did not accept requests within 5s" INFRA_INIT_WAIT_TIMEOUT_SECONDS=5
elapsed=$(( $(date +%s) - start ))
if [ "$elapsed" -le 40 ]; then pass "case 6 bounded: ${elapsed}s"; else fail "case 6 not bounded: ${elapsed}s"; fi

echo "== case 7: missing credentials (configuration error)"
run_init "case 7" 2 "AWS_SECRET_ACCESS_KEY is required" AWS_SECRET_ACCESS_KEY=

echo "== cleanup of the isolated project $PROJECT only"
dc down --remove-orphans -t 2 >/dev/null 2>&1
left=$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" -q | wc -l | tr -d ' ')
expect_equal "negtest containers left" "0" "$left"

printf 'RESULT %s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
