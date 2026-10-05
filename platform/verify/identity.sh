#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Identity contract verifier (PLAT-INC-003, plan section 8.3, ADR-033, PLAT-IV-003 a).
#
# Requires the project running with local-idp healthy:
#   docker compose up -d --wait local-idp
# Checks discovery/JWKS from the host and from the Compose network, issues tokens
# from both sides, verifies them CRYPTOGRAPHICALLY with the published JWKS
# (local-idp "verify" mode), checks the five identities, an arbitrary CUSTOMER
# subject, rejections (expired, tampered, invalid requests), the load batch
# (> 1.000 distinct tokens, timed) and key rotation on restart.
# Tokens are kept in shell variables / the ephemeral volume and NEVER printed.
#
# Usage: bash platform/verify/identity.sh [--env-file <file>] [--skip-restart]
# -----------------------------------------------------------------------------
set -uo pipefail
export MSYS_NO_PATHCONV=1

ENV_FILE=.env
SKIP_RESTART=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE=$2; shift ;;
    --skip-restart) SKIP_RESTART=1 ;;
  esac
  shift
done
port=$(grep -E '^LOCAL_IDP_HOST_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2 | tr -d '\r')
HOST_URL="http://127.0.0.1:${port:-9000}"
ISSUER="http://local-idp:9000"

dc() { docker compose --env-file "$ENV_FILE" "$@"; }
verify_in_network() { dc run --rm --no-deps -T local-idp verify "$@" 2>/dev/null | tr -d '\r'; }

PASSED=0 FAILED=0
pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
expect_contains() { if printf '%s' "$3" | grep -qF -- "$2"; then pass "$1"; else fail "$1: missing [$2] in [$3]"; fi; }

token_from_host() { # token_from_host <form>  -> prints the access token (captured, never echoed by callers)
  curl -s -X POST "$HOST_URL/token" -H 'Content-Type: application/x-www-form-urlencoded' --data "$1" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
status_from_host() { # status_from_host <method> <path> [form]
  if [ -n "${3:-}" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "$HOST_URL$2" -H 'Content-Type: application/x-www-form-urlencoded' --data "$3"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "$HOST_URL$2"
  fi
}

echo "== 1. discovery and JWKS from the host ($HOST_URL)"
discovery=$(curl -s "$HOST_URL/.well-known/openid-configuration")
expect_contains "host discovery issuer" "\"issuer\":\"$ISSUER\"" "$discovery"
expect_contains "host discovery jwks_uri" "\"jwks_uri\":\"$ISSUER/.well-known/jwks.json\"" "$discovery"
host_jwks_sha=$(curl -s "$HOST_URL/.well-known/jwks.json" | sha256sum | cut -c1-16)
expect_contains "host JWKS RS256 key" '"alg":"RS256"' "$(curl -s "$HOST_URL/.well-known/jwks.json")"

echo "== 2. discovery and JWKS from the Compose network ($ISSUER)"
probe=$(dc run --rm --no-deps -T local-idp probe "$ISSUER/.well-known/openid-configuration" "$ISSUER/.well-known/jwks.json" 2>/dev/null | tr -d '\r')
printf '%s\n' "$probe" | sed 's/^/      /'
expect_contains "network discovery 200" "200 $ISSUER/.well-known/openid-configuration" "$probe"
expect_contains "network JWKS 200 and same key set as host (sha256 $host_jwks_sha)" "200 $ISSUER/.well-known/jwks.json sha256=$host_jwks_sha" "$probe"

echo "== 3. five deterministic identities issued from the host, verified with the published JWKS"
tokens=""
for identity in admin customer-a customer-b admin-customer no-groups; do
  t=$(token_from_host "identity=$identity")
  [ -n "$t" ] || fail "no token for $identity"
  tokens="$tokens$t"$'\n'
done
out=$(printf '%s' "$tokens" | verify_in_network)
printf '%s\n' "$out" | sed 's/^/      /'
expect_contains "admin" "VALID sub=admin cognito:groups=[ADMIN] token_use=access client_id=ticketing-local-client iss=$ISSUER" "$out"
expect_contains "customer-a" "VALID sub=customer-a cognito:groups=[CUSTOMER] token_use=access" "$out"
expect_contains "customer-b" "VALID sub=customer-b cognito:groups=[CUSTOMER] token_use=access" "$out"
expect_contains "admin-customer" "VALID sub=admin-customer cognito:groups=[ADMIN, CUSTOMER] token_use=access" "$out"
expect_contains "no-groups (claim absent)" "VALID sub=no-groups cognito:groups=absent token_use=access" "$out"
expect_contains "no aud claim" "aud=absent" "$out"
expect_contains "default validity 3600 s on the five tokens" "5" "$(printf '%s\n' "$out" | grep -cE 'exp_in=(3600|359[0-9])s')"
expect_contains "all five valid" "SUMMARY tokens=5 valid=5 distinct_sub=5 distinct_jti=5" "$out"

echo "== 4. arbitrary CUSTOMER subject from the host, custom validity"
t=$(token_from_host "sub=qa.arbitrary-subject_01&groups=CUSTOMER&expires_in=600")
out=$(printf '%s\n' "$t" | verify_in_network)
printf '%s\n' "$out" | sed 's/^/      /'
expect_contains "arbitrary subject" "VALID sub=qa.arbitrary-subject_01 cognito:groups=[CUSTOMER]" "$out"
expect_contains "custom expires_in honoured" "exp_in=" "$(printf '%s' "$out" | grep -E 'exp_in=(600|59[0-9])s')"
max_sub=$(printf 'a%.0s' $(seq 1 128))
t=$(token_from_host "sub=$max_sub&groups=CUSTOMER")
out=$(printf '%s\n' "$t" | verify_in_network)
expect_contains "128-character subject accepted" "SUMMARY tokens=1 valid=1" "$out"

echo "== 5. tokens issued from inside the network: same issuer as host-issued tokens"
out=$(verify_in_network --issue "identity=customer-a" --issue "sub=network-subject&groups=CUSTOMER")
printf '%s\n' "$out" | sed 's/^/      /'
expect_contains "network-issued customer-a, iss=$ISSUER" "VALID sub=customer-a cognito:groups=[CUSTOMER] token_use=access client_id=ticketing-local-client iss=$ISSUER " "$out"
expect_contains "network-issued arbitrary subject" "VALID sub=network-subject cognito:groups=[CUSTOMER]" "$out"

echo "== 6. rejections"
t=$(token_from_host "identity=customer-a&expires_in=1")
sleep 2   # let the 1-second token expire (test wait, not a readiness check)
expect_contains "expired token rejected" "INVALID expired" "$(printf '%s\n' "$t" | verify_in_network)"
t=$(token_from_host "identity=customer-a")
sig=${t##*.}
first=${sig:0:1}; [ "$first" = "A" ] && repl=B || repl=A
tampered_sig="${t%.*}.${repl}${sig:1}"
expect_contains "tampered signature rejected" "INVALID signature" "$(printf '%s\n' "$tampered_sig" | verify_in_network)"
header=${t%%.*}; rest=${t#*.}; payload=${rest%%.*}
forged_payload=$(printf '{"sub":"customer-a","cognito:groups":["ADMIN"],"iss":"%s","client_id":"ticketing-local-client","token_use":"access","iat":1,"exp":4102444800,"jti":"x"}' "$ISSUER" \
  | base64 -w0 | tr '+/' '-_' | tr -d '=')
expect_contains "forged payload (privilege escalation) rejected" "INVALID signature" "$(printf '%s\n' "$header.$forged_payload.$sig" | verify_in_network)"
expect_contains "wrong expected issuer rejected" "INVALID iss" "$(printf '%s\n' "$t" | verify_in_network --issuer http://127.0.0.1:9000)"
expect_contains "unknown identity -> 400" "400" "$(status_from_host POST /token 'identity=root')"
expect_contains "subject of 129 characters -> 400" "400" "$(status_from_host POST /token "sub=${max_sub}a&groups=CUSTOMER")"
expect_contains "subject with invalid characters -> 400" "400" "$(status_from_host POST /token 'sub=a%20b&groups=CUSTOMER')"
expect_contains "unknown group -> 400" "400" "$(status_from_host POST /token 'sub=x&groups=ROOT')"
expect_contains "identity and sub together -> 400" "400" "$(status_from_host POST /token 'identity=admin&sub=x')"
expect_contains "expires_in above maximum -> 400" "400" "$(status_from_host POST /token 'identity=admin&expires_in=86401')"
expect_contains "GET /token -> 405" "405" "$(status_from_host GET /token)"
expect_contains "unknown path -> 404" "404" "$(status_from_host GET /nope)"

echo "== 7. load batch (profile load): > 1.000 distinct CUSTOMER tokens"
start=$(date +%s%N)
gen=$(dc --profile load up load-token-generator --no-log-prefix 2>&1 | tr -d '\r')
end=$(date +%s%N)
gen_code=$(docker inspect -f '{{.State.ExitCode}}' "$(dc --profile load ps -a -q load-token-generator)")
printf '%s\n' "$gen" | grep 'local-idp:' | sed 's/^/      /'
expect_contains "generator exit 0" "0" "$gen_code"
pass "generator wall time incl. container start: $(( (end - start) / 1000000 )) ms"
out=$(dc run --rm --no-deps -T -v ticketing-platform_load-tokens:/tokens:ro local-idp verify --csv /tokens/customer-tokens.csv 2>/dev/null | tr -d '\r')
printf '%s\n' "$out" | sed 's/^/      /'
count=$(grep -E '^LOAD_TOKEN_COUNT=' "$ENV_FILE" | tail -1 | cut -d= -f2 | tr -d '\r'); count=${count:-1200}
expect_contains "batch: $count valid, distinct sub and jti" "SUMMARY tokens=$count valid=$count distinct_sub=$count distinct_jti=$count" "$out"
manifest=$(dc run --rm --no-deps -T -v ticketing-platform_load-tokens:/tokens:ro --entrypoint cat local-idp /tokens/manifest.json 2>/dev/null | tr -d '\r')
printf '      manifest: %s\n' "$manifest"
expect_contains "manifest count" "\"count\":$count" "$manifest"
if printf '%s' "$manifest" | grep -q 'eyJ'; then fail "manifest contains a token"; else pass "manifest contains no token"; fi
if printf '%s' "$gen" | grep -q 'eyJ'; then fail "generator log contains a token"; else pass "generator log contains no token"; fi
if dc logs local-idp 2>&1 | grep -q 'eyJ'; then fail "local-idp log contains a token"; else pass "local-idp log contains no token"; fi
perms=$(dc run --rm --no-deps -T -v ticketing-platform_load-tokens:/tokens:ro --entrypoint sh local-idp -c 'stat -c "%a %u" /tokens/customer-tokens.csv /tokens/manifest.json' 2>/dev/null | tr -d '\r' | tr '\n' ' ')
expect_contains "batch files readable by any uid (644, owner 10001)" "644 10001 644 10001" "$perms"

if [ "$SKIP_RESTART" -eq 0 ]; then
  echo "== 8. restarting local-idp rotates the key: earlier tokens are rejected"
  t=$(token_from_host "identity=customer-a")
  dc restart local-idp >/dev/null 2>&1 && dc up -d --wait local-idp >/dev/null 2>&1
  expect_contains "token issued before restart rejected" "INVALID unknown kid" "$(printf '%s\n' "$t" | verify_in_network)"
  out=$(dc run --rm --no-deps -T -v ticketing-platform_load-tokens:/tokens:ro local-idp verify --csv /tokens/customer-tokens.csv 2>/dev/null | tr -d '\r' | tail -1)
  expect_contains "load batch invalid after restart (regenerate it)" "valid=0" "$out"
fi

printf 'RESULT %s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
