# Escenarios de resiliencia

Tres procedimientos de [ADR-038](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-038-test-strategy-v2.md) para demostrar sobre Docker Compose cómo se comporta el sistema cuando cae una pieza.

**Estado de los tres: `NOT_YET_VALIDATED_BY_QA`.** Los comandos se ejecutaron durante la verificación de esta documentación (DOC-INC-005, 2026-10-05) para comprobar que existen y que las señales se observan; eso no es una validación de QA ni un resultado de prueba certificado. Las observaciones se transcriben tal como ocurrieron en ese entorno.

Mecanismos que entran en juego: circuit breakers del Payment Mock y de la publicación en SQS, pausa del consumo, lease del PaymentAttempt, backoff por visibilidad y expiración aislada ([ADR-035](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-035-error-model-reactive-retry-circuit-breaker.md), [ADR-029](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-029-sqs-operational-policy-orders-and-provisioning.md), [ADR-028](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-028-expiration-process-isolated-scheduling.md)). Diagramas en [`diagrams.md`](diagrams.md#13-caída-del-payment-mock-y-circuit-breaker).

## 1. Preparación común

Requisitos: entorno completo levantado (`docker compose up -d --wait`), `bash` y `curl` (Git Bash en Windows), desde la raíz del repositorio. Las acciones solo afectan a este proyecto de Compose.

```bash
API=http://localhost:8080/api/v1
PAYMENT_MOCK_API_KEY=$(grep '^PAYMENT_MOCK_API_KEY=' .env | cut -d= -f2- | tr -d '\r')
MOCK_URL=http://localhost:$(grep '^PAYMENT_MOCK_HOST_PORT=' .env | cut -d= -f2 | tr -d '\r')
token() { curl -s -X POST http://localhost:9000/token -d "$1" | sed -E 's/.*"access_token" *: *"([^"]+)".*/\1/'; }
order_status() { curl -s "$API/orders/$1" -H "Authorization: Bearer $2" | sed -E 's/.*"status" *: *"([^"]+)".*/\1/'; echo; }
# The worker publishes no ports: read its metrics from a short-lived container on the Compose network
worker_circuits() { MSYS_NO_PATHCONV=1 docker compose run --rm --no-deps -T --entrypoint sh infra-init \
  -c 'curl -s http://ticketing-worker:8081/actuator/prometheus' 2>/dev/null | grep '^ticketing_circuit_state.* 1.0'; }
ADMIN_TOKEN=$(token identity=admin)
STARTS_AT=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)   # GNU date; on macOS: date -u -v+30d +%Y-%m-%dT%H:%M:%SZ
EVENT_ID=$(curl -s -X POST "$API/events" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: evt-resilience-$(date +%s)" \
  -d "{\"name\":\"Resilience\",\"venue\":\"Arena\",\"startsAt\":\"$STARTS_AT\",\"capacity\":10,\"inventory\":{\"sections\":[{\"code\":\"A\",\"rows\":[{\"label\":\"1\",\"seats\":10}]}]}}" \
  | sed -E 's/.*"eventId" *: *"([^"]+)".*/\1/')
sleep 3; echo "event $EVENT_ID"
```

Cada procedimiento usa sujetos `CUSTOMER` propios y Ticket distintos del mismo Event. Se pueden ejecutar en orden en la misma sesión de shell.

## 2. Procedimiento 1: detener `ticketing-worker` a mitad de un pago

**Qué se espera (ADR-038, escenario 1):** al volver el `worker`, el lease del PaymentAttempt vence y otro consumo reanuda **el mismo** PaymentAttempt; el Payment Mock registra un único intento.

`docker compose stop` hace un apagado ordenado que termina lo que está en vuelo, así que para simular una caída se usa `kill`. Una regla `LATENCY` de 2,5 s mantiene la autorización en curso mientras se detiene el contenedor.

```bash
SUB1=resilience-1-$(date +%s); BUYER1=$(token "sub=$SUB1&groups=CUSTOMER")
curl -s -o /dev/null -X POST "$MOCK_URL/control/reset" -H "X-Api-Key: $PAYMENT_MOCK_API_KEY"
curl -s -X POST "$MOCK_URL/control/rules" -H "X-Api-Key: $PAYMENT_MOCK_API_KEY" -H 'Content-Type: application/json' \
  -d "{\"match\":{\"customerRef\":\"$SUB1\"},\"behaviour\":{\"type\":\"LATENCY\",\"addedLatencyMs\":2500}}"; echo
ORDER1=$(curl -s -X POST "$API/orders" -H "Authorization: Bearer $BUYER1" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ord-resilience-1-$(date +%s)" -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":[\"A-1-1\"]}" \
  | sed -E 's/.*"orderId" *: *"([^"]+)".*/\1/')
sleep 1; docker compose kill ticketing-worker                     # simulated crash during the authorization
curl -s "$MOCK_URL/control/authorizations/$ORDER1-1" -H "X-Api-Key: $PAYMENT_MOCK_API_KEY"; echo   # 1 invocation, no result
docker compose start ticketing-worker
for i in $(seq 1 60); do S=$(order_status "$ORDER1" "$BUYER1"); [ "$S" != CREATED ] && break; sleep 2; done; echo "order: $S"
curl -s "$MOCK_URL/control/authorizations/$ORDER1-1" -H "X-Api-Key: $PAYMENT_MOCK_API_KEY"; echo   # same attempt, one result
```

| Señal | Observado en DOC-INC-005 |
|---|---|
| Autorización tras el `kill` | 1 invocación del intento `<orderId>-1`, sin resultado todavía |
| Estado de la Order | `CREATED` mientras el `worker` no está; `CONFIRMED` unos 62 s después de la compra (visibilidad de 60 s y lease de 45 s) |
| Payment Mock al final | 2 invocaciones del **mismo** `paymentAttemptId`, un único resultado `APPROVED` (la segunda es una repetición idempotente) |
| `ticketing-worker` | Vuelve a `healthy` tras `start` |

Restauración: ninguna adicional; el `worker` ya está arrancado. `docker compose ps` debe mostrar todo sano.

## 3. Procedimiento 2: detener `payment-mock`

**Qué se espera (ADR-038, escenario 2):** el circuito del Payment Mock se abre, el consumo de Orders y los reversos se pausan sin gastar recepciones, la expiración continúa; al volver el mock, el circuito pasa a semiabierto y el consumo se reanuda.

```bash
worker_circuits                                                    # payment-mock CLOSED
docker compose stop payment-mock
ORDERS=(); BUYERS=()
for n in 2 3 4 5 6; do
  B=$(token "sub=resilience-2-$n-$(date +%s)&groups=CUSTOMER")
  O=$(curl -s -X POST "$API/orders" -H "Authorization: Bearer $B" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ord-resilience-2-$n-$(date +%s)" -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":[\"A-1-$n\"]}" \
    | sed -E 's/.*"orderId" *: *"([^"]+)".*/\1/')
  ORDERS+=("$O"); BUYERS+=("$B")
done
states() { for k in 0 1 2 3 4; do order_status "${ORDERS[$k]}" "${BUYERS[$k]}"; done | sort | uniq -c; }
sleep 15; worker_circuits; states                                  # payment-mock OPEN; Orders stay CREATED
docker compose logs ticketing-worker | grep -c '"event":"circuit.opened"'
docker compose up -d --wait payment-mock                           # the mock restarts empty (rules and records are lost)
for i in $(seq 1 45); do states | grep -q CREATED || break; sleep 2; done; states; worker_circuits
```

| Señal | Observado en DOC-INC-005 |
|---|---|
| Circuito `payment-mock` del `worker` | `OPEN` unos 10 s después de detener el mock (`ticketing_circuit_state`), log `circuit.opened` |
| Orders creadas durante la caída | Las 5 siguen `CREATED` mientras el mock está caído (consumo en pausa) |
| Tras `up -d --wait payment-mock` | Las 5 terminan `CONFIRMED` unos 40 s después de reiniciar el mock; circuito de nuevo `CLOSED`; el mock vuelve sin reglas |
| Expiración y pausa de reversos | No observadas en esta ejecución (no había Reservation vencidas ni reversos pendientes); verificadas en las pruebas del backend con tiempo virtual (INC-009) |

Restauración: `docker compose up -d --wait payment-mock` (ya incluido). Si una Order agotara sus recepciones antes de que vuelva el mock, terminaría `FAILED`; la pausa del consumo existe para evitarlo.

## 4. Procedimiento 3: detener `localstack` (cola de mensajes)

**Qué se espera (ADR-038, escenario 3):** una vez abierto el circuito de publicación, la compra responde 503 con `Retry-After` sin modificar el inventario; al recuperar la cola, las compras vuelven a crear Orders.

Se usa `pause`/`unpause`, que conserva las colas. Un `docker compose restart localstack` las borraría y obligaría a recrearlas con `infra-init` (ver [`operations.md`](operations.md#7-recuperación-tras-reiniciar-un-emulador)).

```bash
BUYER3=$(token "sub=resilience-3-$(date +%s)&groups=CUSTOMER")
available() { curl -s "$API/events/$EVENT_ID/availability" -H "Authorization: Bearer $BUYER3" | sed -E 's/.*"availableCount" *: *([0-9]+).*/\1/'; }
echo "available before: $(available)"
docker compose pause localstack
for n in 1 2 3 4 5 6; do
  curl -s -D - -o /dev/null -X POST "$API/orders" -H "Authorization: Bearer $BUYER3" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ord-resilience-3-$n-$(date +%s)" -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":[\"A-1-7\"]}" \
    | grep -iE '^(HTTP|retry-after)' | tr -d '\r' | tr '\n' ' '; echo
done
echo "available during the outage: $(available)"
curl -s -o /dev/null -w 'readyz %{http_code}\n' http://localhost:8080/readyz
docker compose unpause localstack
for i in $(seq 1 15); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders" -H "Authorization: Bearer $BUYER3" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ord-resilience-3-after-$i-$(date +%s)" -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":[\"A-1-8\"]}")
  [ "$CODE" = 201 ] && break; sleep 2
done; echo "purchase after recovery: $CODE"
```

| Señal | Observado en DOC-INC-005 |
|---|---|
| Primeras compras con la cola pausada | 201 con la Order `FAILED` y causa `PROCESSING_UNAVAILABLE`, en unos 1,8 s cada una (presupuesto de publicación de 2 s; la compensación libera los Ticket). Fueron 4 en la primera ejecución y 2 en la segunda: el número depende de las llamadas previas en la ventana del circuito |
| Con el circuito de publicación abierto | 503 `SERVICE_UNAVAILABLE` con `Retry-After: 10`, en menos de 10 ms, sin reservar |
| Inventario durante la caída | `availableCount` igual que antes de pausar la cola (en ambas ejecuciones) |
| Salud del `api` | `/readyz` sigue en 200; circuito `sqs-publication` `OPEN` en `:8081/actuator/prometheus` |
| Tras `unpause` | Unos 8 s después, la compra vuelve a responder 201 `CREATED`; el circuito pasa por `HALF_OPEN` y se cierra tras 2 llamadas correctas; `localstack` vuelve a `healthy` en unos segundos |

Restauración: `docker compose unpause localstack` (ya incluido) y comprobar `docker compose ps`.

## 5. Después de los procedimientos

```bash
curl -s -o /dev/null -w 'mock reset %{http_code}\n' -X POST "$MOCK_URL/control/reset" -H "X-Api-Key: $PAYMENT_MOCK_API_KEY"
docker compose ps -a --format '{{.Service}} {{.Status}}'
```

Para QA quedan, además de la validación formal de los tres escenarios: medir la expiración y la pausa de reversos con el Payment Mock caído, repetirlos bajo carga y comprobar las invariantes de ADR-038 al terminar.
