#!/bin/sh
# -----------------------------------------------------------------------------
# infra-init (ADR-036, PLAT-INC-002): one-shot, idempotent creation and
# validation of the local resources of the Ticketing platform.
#
#   - table <TICKETING_TABLE_NAME> with PK/SK, on-demand, GSI1..GSI4 and TTL `ttl`
#     (data model v2 sections 2.1-2.2; literal definition in resources/table.json,
#     canonical expectation in resources/table.expected)
#   - four SQS Standard queues with attributes and redrive (messaging v2 section 1;
#     resources/queues.def)
#
# Existing resources are validated, never modified or deleted (PLAT-IV-005 a).
# The only completion step applied to an existing table is enabling TTL on `ttl`
# when TTL is still DISABLED (interrupted first run; plan section 6.3 step 3).
#
# Exit codes: 0 everything matches; 1 incompatible resource; 2 configuration
# error; 3 emulator not ready within the timeout; 4 unexpected AWS error.
# Never prints credentials.
# -----------------------------------------------------------------------------
set -eu

RESOURCES_DIR=${INFRA_INIT_RESOURCES_DIR:-/opt/infra-init/resources}
DYNAMODB_ENDPOINT=${DYNAMODB_ENDPOINT:-http://dynamodb-local:8000}
SQS_ENDPOINT=${SQS_ENDPOINT:-http://localstack:4566}
WAIT_TIMEOUT_SECONDS=${INFRA_INIT_WAIT_TIMEOUT_SECONDS:-60}

TICKETING_TABLE_NAME=${TICKETING_TABLE_NAME:-ticketing}
ORDERS_QUEUE_NAME=${ORDERS_QUEUE_NAME:-ticketing-orders}
ORDERS_DLQ_NAME=${ORDERS_DLQ_NAME:-ticketing-orders-dlq}
PROVISIONING_QUEUE_NAME=${PROVISIONING_QUEUE_NAME:-ticketing-event-provisioning}
PROVISIONING_DLQ_NAME=${PROVISIONING_DLQ_NAME:-ticketing-event-provisioning-dlq}

export AWS_PAGER=""
export AWS_RETRY_MODE=standard
export AWS_MAX_ATTEMPTS=2

AWS_TIMEOUTS="--cli-connect-timeout 2 --cli-read-timeout 15"

log() { printf '%s infra-init: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { code=$1; shift; printf '%s infra-init: ERROR: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit "$code"; }

# shellcheck disable=SC2086
ddb() { aws $AWS_TIMEOUTS --endpoint-url "$DYNAMODB_ENDPOINT" dynamodb "$@"; }
# shellcheck disable=SC2086
sqs() { aws $AWS_TIMEOUTS --endpoint-url "$SQS_ENDPOINT" sqs "$@"; }

# ---------------------------------------------------------------- configuration
check_config() {
  [ -n "${AWS_REGION:-}" ] || die 2 "AWS_REGION is required"
  [ -n "${AWS_ACCESS_KEY_ID:-}" ] || die 2 "AWS_ACCESS_KEY_ID is required (fictitious, local emulators only)"
  [ -n "${AWS_SECRET_ACCESS_KEY:-}" ] || die 2 "AWS_SECRET_ACCESS_KEY is required (fictitious, local emulators only)"
  for f in table.json table.expected queues.def; do
    [ -r "$RESOURCES_DIR/$f" ] || die 2 "missing resource definition $RESOURCES_DIR/$f"
  done
  case "$WAIT_TIMEOUT_SECONDS" in ''|*[!0-9]*) die 2 "INFRA_INIT_WAIT_TIMEOUT_SECONDS must be a positive integer" ;; esac
  for n in "$TICKETING_TABLE_NAME" "$ORDERS_QUEUE_NAME" "$ORDERS_DLQ_NAME" "$PROVISIONING_QUEUE_NAME" "$PROVISIONING_DLQ_NAME"; do
    case "$n" in ''|*[!A-Za-z0-9_.-]*) die 2 "invalid resource name '$n'" ;; esac
  done
}

queue_name_of() {
  case "$1" in
    ORDERS_QUEUE) printf '%s' "$ORDERS_QUEUE_NAME" ;;
    ORDERS_DLQ) printf '%s' "$ORDERS_DLQ_NAME" ;;
    PROVISIONING_QUEUE) printf '%s' "$PROVISIONING_QUEUE_NAME" ;;
    PROVISIONING_DLQ) printf '%s' "$PROVISIONING_DLQ_NAME" ;;
    *) die 2 "unknown queue role '$1' in queues.def" ;;
  esac
}

# --------------------------------------------------------------- readiness wait
# Bounded wait: each emulator must accept a real operation (not only an open port).
wait_until_ready() {
  label=$1; shift
  deadline=$(( $(date +%s) + WAIT_TIMEOUT_SECONDS ))
  while :; do
    if last_error=$("$@" 2>&1 >/dev/null); then
      log "$label is ready"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      die 3 "$label did not accept requests within ${WAIT_TIMEOUT_SECONDS}s; last error: $(printf '%s' "$last_error" | tr '\n' ' ' | cut -c1-300)"
    fi
    sleep 1
  done
}

# ------------------------------------------------------------------ comparison
# Compares two canonical files as sets of lines and prints a precise diff.
# Prints the lines of file $2 that are absent from file $1 (grep status 1 = none, not an error).
lines_not_in() {
  set +e
  grep -vxF -f "$1" "$2"
  rc=$?
  set -e
  [ "$rc" -le 1 ]
}

compare_canonical() {
  what=$1 expected=$2 actual=$3
  missing=$(lines_not_in "$actual" "$expected") || die 4 "cannot compare $what"
  unexpected=$(lines_not_in "$expected" "$actual") || die 4 "cannot compare $what"
  if [ -z "$missing" ] && [ -z "$unexpected" ]; then
    return 0
  fi
  {
    printf 'INCOMPATIBLE %s (nothing was modified or deleted):\n' "$what"
    [ -z "$missing" ] || printf '%s\n' "$missing" | sed 's/^/  expected: /'
    [ -z "$unexpected" ] || printf '%s\n' "$unexpected" | sed 's/^/  actual:   /'
    printf 'Remedy: local data is ephemeral; run "docker compose down" for this project and start it again.\n'
  } >&2
  return 1
}

# ----------------------------------------------------------------------- table
TABLE_QUERY="[[join(' ', ['KEYS', join(',', Table.KeySchema[].join(':', [AttributeName, KeyType]))]), \
join(' ', ['ATTRIBUTES', join(',', sort(Table.AttributeDefinitions[].join(':', [AttributeName, AttributeType])))]), \
join(' ', ['BILLING', Table.BillingModeSummary.BillingMode || 'PROVISIONED'])], \
sort_by(Table.GlobalSecondaryIndexes || \`[]\`, &IndexName)[].join(' ', ['INDEX', IndexName, \
join(',', KeySchema[].join(':', [AttributeName, KeyType])), Projection.ProjectionType, \
join(',', sort(Projection.NonKeyAttributes || \`[]\`)) || '-'])]"

TABLE_STATUS_QUERY="join(' ', [Table.TableStatus, join(',', (Table.GlobalSecondaryIndexes || \`[]\`)[].IndexStatus)])"

TTL_QUERY="join(' ', ['TTL', TimeToLiveDescription.TimeToLiveStatus, TimeToLiveDescription.AttributeName || '-'])"

describe_table_canonical() {
  canonical=$(ddb describe-table --table-name "$TICKETING_TABLE_NAME" --query "$TABLE_QUERY" --output text) || return 1
  printf '%s\n' "$canonical" | tr '\t' '\n'
}

describe_ttl_canonical() {
  ddb describe-time-to-live --table-name "$TICKETING_TABLE_NAME" --query "$TTL_QUERY" --output text
}

wait_table_active() {
  deadline=$(( $(date +%s) + WAIT_TIMEOUT_SECONDS ))
  while :; do
    status=$(ddb describe-table --table-name "$TICKETING_TABLE_NAME" --query "$TABLE_STATUS_QUERY" --output text) \
      || die 4 "describe-table failed while waiting for ACTIVE"
    case "$status" in
      *CREATING*|*UPDATING*|*DELETING*) : ;;
      ACTIVE*) return 0 ;;
      *) die 1 "table $TICKETING_TABLE_NAME is in unexpected state: $status" ;;
    esac
    [ "$(date +%s)" -lt "$deadline" ] || die 3 "table $TICKETING_TABLE_NAME not ACTIVE within ${WAIT_TIMEOUT_SECONDS}s ($status)"
    sleep 1
  done
}

ensure_table() {
  if out=$(ddb describe-table --table-name "$TICKETING_TABLE_NAME" --query 'Table.TableName' --output text 2>&1); then
    log "table $TICKETING_TABLE_NAME exists: validating"
  else
    case "$out" in
      *ResourceNotFoundException*)
        log "table $TICKETING_TABLE_NAME does not exist: creating (PK/SK, PAY_PER_REQUEST, GSI1..GSI4)"
        ddb create-table --table-name "$TICKETING_TABLE_NAME" \
          --cli-input-json "file://$RESOURCES_DIR/table.json" --query 'TableDescription.TableName' --output text >/dev/null \
          || die 4 "create-table $TICKETING_TABLE_NAME failed"
        ;;
      *) die 4 "describe-table $TICKETING_TABLE_NAME failed: $out" ;;
    esac
  fi
  wait_table_active

  ttl=$(describe_ttl_canonical) || die 4 "describe-time-to-live $TICKETING_TABLE_NAME failed"
  case "$ttl" in
    "TTL DISABLED -")
      log "TTL of $TICKETING_TABLE_NAME is DISABLED: enabling on attribute ttl"
      ddb update-time-to-live --table-name "$TICKETING_TABLE_NAME" \
        --time-to-live-specification "Enabled=true,AttributeName=ttl" --output text >/dev/null \
        || die 4 "update-time-to-live $TICKETING_TABLE_NAME failed"
      ;;
  esac

  actual=$(mktemp)
  deadline=$(( $(date +%s) + WAIT_TIMEOUT_SECONDS ))
  while :; do
    ttl=$(describe_ttl_canonical) || die 4 "describe-time-to-live $TICKETING_TABLE_NAME failed"
    case "$ttl" in "TTL ENABLING ttl") : ;; *) break ;; esac
    [ "$(date +%s)" -lt "$deadline" ] || break
    sleep 1
  done
  { describe_table_canonical || die 4 "describe-table $TICKETING_TABLE_NAME failed"; printf '%s\n' "$ttl"; } > "$actual"
  if ! compare_canonical "table $TICKETING_TABLE_NAME" "$RESOURCES_DIR/table.expected" "$actual"; then
    rm -f "$actual"
    exit 1
  fi
  rm -f "$actual"
  log "table $TICKETING_TABLE_NAME matches data model v2 (keys, billing, GSI1..GSI4, projections, TTL ttl)"
}

# ---------------------------------------------------------------------- queues
QUEUE_QUERY="[Attributes.VisibilityTimeout, Attributes.ReceiveMessageWaitTimeSeconds, Attributes.MessageRetentionPeriod, \
Attributes.DelaySeconds, Attributes.FifoQueue || 'false', Attributes.QueueArn, Attributes.RedrivePolicy || 'NONE']"

queue_url() { sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>&1; }

queue_arn() { sqs get-queue-attributes --queue-url "$1" --attribute-names QueueArn --query Attributes.QueueArn --output text; }

# Prints "maxReceiveCount:<n>,deadLetterTargetArn:<arn>" from a RedrivePolicy JSON string, or NONE.
normalize_redrive() {
  if [ "$1" = "NONE" ]; then printf 'NONE'; return 0; fi
  count=$(printf '%s' "$1" | sed -n 's/.*"maxReceiveCount"[[:space:]]*:[[:space:]]*"\{0,1\}\([0-9][0-9]*\)"\{0,1\}.*/\1/p')
  target=$(printf '%s' "$1" | sed -n 's/.*"deadLetterTargetArn"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  printf 'maxReceiveCount:%s,deadLetterTargetArn:%s' "${count:-?}" "${target:-?}"
}

ensure_queue() {
  role=$1 visibility=$2 wait=$3 retention=$4 delay=$5 max_receive=$6 dlq_role=$7
  name=$(queue_name_of "$role")
  case "$name" in *.fifo) die 2 "queue $name must be Standard (no .fifo suffix)" ;; esac

  expected_wait=$wait
  [ "$wait" != "-" ] || expected_wait=0
  expected_redrive=NONE
  if [ "$dlq_role" != "-" ]; then
    dlq_name=$(queue_name_of "$dlq_role")
    dlq_url=$(queue_url "$dlq_name") || die 4 "dead-letter queue $dlq_name must exist before $name: $dlq_url"
    dlq_arn=$(queue_arn "$dlq_url") || die 4 "cannot read the ARN of $dlq_name"
    expected_redrive="maxReceiveCount:$max_receive,deadLetterTargetArn:$dlq_arn"
  fi

  if url=$(queue_url "$name"); then
    log "queue $name exists: validating"
  else
    case "$url" in
      *NonExistentQueue*|*QueueDoesNotExist*)
        attrs="\"VisibilityTimeout\":\"$visibility\",\"MessageRetentionPeriod\":\"$retention\",\"DelaySeconds\":\"$delay\""
        [ "$wait" = "-" ] || attrs="$attrs,\"ReceiveMessageWaitTimeSeconds\":\"$wait\""
        if [ "$dlq_role" != "-" ]; then
          attrs="$attrs,\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"$max_receive\\\"}\""
        fi
        log "queue $name does not exist: creating"
        url=$(sqs create-queue --queue-name "$name" --attributes "{$attrs}" --query QueueUrl --output text) \
          || die 4 "create-queue $name failed"
        ;;
      *) die 4 "get-queue-url $name failed: $url" ;;
    esac
  fi

  values=$(sqs get-queue-attributes --queue-url "$url" --attribute-names All --query "$QUEUE_QUERY" --output text) \
    || die 4 "get-queue-attributes $name failed"
  # Fields are tab separated; RedrivePolicy is the last one and contains no tabs.
  tab=$(printf '\t')
  IFS="$tab" read -r a_visibility a_wait a_retention a_delay a_fifo a_arn a_redrive <<EOF
$values
EOF
  a_redrive=$(normalize_redrive "$a_redrive")

  exp=$(mktemp); act=$(mktemp)
  printf '%s\n' "QUEUE $name Standard" "VisibilityTimeout=$visibility" "ReceiveMessageWaitTimeSeconds=$expected_wait" \
    "MessageRetentionPeriod=$retention" "DelaySeconds=$delay" "RedrivePolicy=$expected_redrive" > "$exp"
  a_type=Standard; [ "$a_fifo" = "false" ] || a_type=FIFO
  printf '%s\n' "QUEUE $name $a_type" "VisibilityTimeout=$a_visibility" "ReceiveMessageWaitTimeSeconds=$a_wait" \
    "MessageRetentionPeriod=$a_retention" "DelaySeconds=$a_delay" "RedrivePolicy=$a_redrive" > "$act"
  if ! compare_canonical "queue $name" "$exp" "$act"; then
    rm -f "$exp" "$act"
    exit 1
  fi
  rm -f "$exp" "$act"
  log "queue $name matches messaging v2 ($url, $a_arn)"
}

ensure_queues() {
  sed -e 's/\r$//' -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$RESOURCES_DIR/queues.def" > /tmp/infra-init-queues.$$ \
    || die 2 "cannot read queues.def"
  while read -r role visibility wait retention delay max_receive dlq_role; do
    [ -n "$dlq_role" ] || die 2 "malformed line for role '$role' in queues.def"
    ensure_queue "$role" "$visibility" "$wait" "$retention" "$delay" "$max_receive" "$dlq_role"
  done < /tmp/infra-init-queues.$$
  rm -f /tmp/infra-init-queues.$$
}

# ------------------------------------------------------------------------ main
check_config
log "start: dynamodb=$DYNAMODB_ENDPOINT sqs=$SQS_ENDPOINT region=$AWS_REGION table=$TICKETING_TABLE_NAME"
log "queues: $ORDERS_QUEUE_NAME, $ORDERS_DLQ_NAME, $PROVISIONING_QUEUE_NAME, $PROVISIONING_DLQ_NAME"
wait_until_ready "dynamodb-local ($DYNAMODB_ENDPOINT)" ddb list-tables --max-items 1
wait_until_ready "localstack sqs ($SQS_ENDPOINT)" sqs list-queues
ensure_table
ensure_queues
log "done: all resources match the approved definitions"
