#!/bin/sh
# -----------------------------------------------------------------------------
# Resource contract verifier (PLAT-INC-002, plan section 8.2).
# Independent of infra-init's own comparison: every expectation below is written
# literally from data model v2 sections 2.1-2.2 and messaging v2 section 1.
#
# Run inside the infra-init image (AWS CLI), on the project network:
#   docker compose run --rm --no-deps -v ./platform/verify:/verify:ro \
#     --entrypoint sh infra-init /verify/resources.sh
# Prints PASS/FAIL per check and the creation marks used to prove idempotency.
# Exit code = number of failed checks (0 = all pass).
# -----------------------------------------------------------------------------
set -u
export AWS_PAGER=""
DDB="aws --endpoint-url ${DYNAMODB_ENDPOINT:-http://dynamodb-local:8000} dynamodb"
SQS="aws --endpoint-url ${SQS_ENDPOINT:-http://localstack:4566} sqs"
T=${TICKETING_TABLE_NAME:-ticketing}
FAILS=0

check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'PASS  %s = %s\n' "$1" "$3"
  else
    printf 'FAIL  %s: expected [%s] actual [%s]\n' "$1" "$2" "$3"
    FAILS=$((FAILS + 1))
  fi
}

q() { $DDB describe-table --table-name "$T" --output text --query "$1" 2>&1 | tr '\t' ' '; }

# --- table
check "table name" "$T" "$(q 'Table.TableName')"
check "table status" "ACTIVE" "$(q 'Table.TableStatus')"
check "key schema" "PK HASH SK RANGE" "$(q 'Table.KeySchema[].[AttributeName,KeyType][]')"
check "PK/SK types" "S S" "$(q "Table.AttributeDefinitions[?AttributeName=='PK' || AttributeName=='SK'].AttributeType")"
check "billing mode" "PAY_PER_REQUEST" "$(q 'Table.BillingModeSummary.BillingMode')"
check "index names" "GSI1 GSI2 GSI3 GSI4" "$(q 'sort(Table.GlobalSecondaryIndexes[].IndexName)')"
for i in 1 2 3 4; do
  check "GSI$i keys" "GSI${i}PK HASH GSI${i}SK RANGE" \
    "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI$i'].KeySchema[][].[AttributeName,KeyType][]")"
  check "GSI$i key types" "S S" \
    "$(q "Table.AttributeDefinitions[?AttributeName=='GSI${i}PK' || AttributeName=='GSI${i}SK'].AttributeType")"
  check "GSI$i status" "ACTIVE" "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI$i'].IndexStatus | [0]")"
done
check "GSI1 projection" "INCLUDE" "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].Projection.ProjectionType | [0]")"
check "GSI1 attributes" \
  "availabilityShards capacity createdAt entityType eventId lastProgressAtMs name provisioningRepublishCount provisioningStatus startsAt startsAtMs venue" \
  "$(q "sort(Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].Projection.NonKeyAttributes | [0])")"
check "GSI2 projection" "INCLUDE" "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI2'].Projection.ProjectionType | [0]")"
check "GSI2 attributes" "row seat section ticketId" \
  "$(q "sort(Table.GlobalSecondaryIndexes[?IndexName=='GSI2'].Projection.NonKeyAttributes | [0])")"
check "GSI3 projection" "KEYS_ONLY" "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI3'].Projection.ProjectionType | [0]")"
check "GSI4 projection" "KEYS_ONLY" "$(q "Table.GlobalSecondaryIndexes[?IndexName=='GSI4'].Projection.ProjectionType | [0]")"
check "TTL" "ENABLED ttl" \
  "$($DDB describe-time-to-live --table-name "$T" --output text \
      --query 'TimeToLiveDescription.[TimeToLiveStatus,AttributeName]' 2>&1 | tr '\t' ' ')"
printf 'MARK  table CreationDateTime = %s\n' "$(q 'Table.CreationDateTime')"
printf 'MARK  tables in DynamoDB Local = %s\n' "$($DDB list-tables --output text --query 'TableNames' 2>&1 | tr '\t' ' ')"

# --- queues
ORDERS=${ORDERS_QUEUE_NAME:-ticketing-orders}
ORDERS_DLQ=${ORDERS_DLQ_NAME:-ticketing-orders-dlq}
PROV=${PROVISIONING_QUEUE_NAME:-ticketing-event-provisioning}
PROV_DLQ=${PROVISIONING_DLQ_NAME:-ticketing-event-provisioning-dlq}

all=$($SQS list-queues --output text --query 'sort(QueueUrls[])' 2>&1 | tr '\t' '\n' | sed 's#.*/##' | sort | tr '\n' ' ')
expected_all=$(printf '%s\n' "$ORDERS" "$ORDERS_DLQ" "$PROV" "$PROV_DLQ" | sort | tr '\n' ' ')
check "queue set" "$expected_all" "$all"

queue() { # queue <name> <visibility> <wait> <retention> <delay> <redrive "count arnSuffix" | NONE>
  url=$($SQS get-queue-url --queue-name "$1" --output text --query QueueUrl 2>&1)
  check "$1 url" "http://localstack:4566/queue/${AWS_REGION:-us-east-1}/000000000000/$1" "$url"
  attrs=$($SQS get-queue-attributes --queue-url "$url" --attribute-names All --output text \
    --query 'Attributes.[VisibilityTimeout,ReceiveMessageWaitTimeSeconds,MessageRetentionPeriod,DelaySeconds,FifoQueue || `"false"`]' 2>&1 | tr '\t' ' ')
  check "$1 visibility/wait/retention/delay/fifo" "$2 $3 $4 $5 false" "$attrs"
  redrive=$($SQS get-queue-attributes --queue-url "$url" --attribute-names RedrivePolicy --output text \
    --query 'Attributes.RedrivePolicy || `"NONE"`' 2>&1)
  if [ "$6" = "NONE" ]; then
    check "$1 redrive" "NONE" "$redrive"
  else
    count=$(printf '%s' "$redrive" | sed -n 's/.*"maxReceiveCount"[^0-9]*\([0-9][0-9]*\).*/\1/p')
    target=$(printf '%s' "$redrive" | sed -n 's/.*"deadLetterTargetArn"[^"]*"\([^"]*\)".*/\1/p')
    check "$1 redrive" "$6" "$count $target"
  fi
  printf 'MARK  %s CreatedTimestamp = %s\n' "$1" \
    "$($SQS get-queue-attributes --queue-url "$url" --attribute-names CreatedTimestamp --output text --query Attributes.CreatedTimestamp 2>&1)"
}
ARN_PREFIX="arn:aws:sqs:${AWS_REGION:-us-east-1}:000000000000"
queue "$ORDERS_DLQ" 60 0 1209600 0 NONE
queue "$PROV_DLQ" 120 0 1209600 0 NONE
queue "$ORDERS" 60 20 3600 0 "5 $ARN_PREFIX:$ORDERS_DLQ"
queue "$PROV" 120 20 86400 0 "5 $ARN_PREFIX:$PROV_DLQ"

printf 'RESULT %s failed check(s)\n' "$FAILS"
exit "$FAILS"
