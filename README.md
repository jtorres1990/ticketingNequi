# Ticketing Event Processing Platform

Reactive ticketing backend (Java 25, Spring Boot 4, WebFlux) on DynamoDB and SQS, with an independent Payment Mock and a local environment on Docker Compose.

| Folder | Content |
|---|---|
| `ticketing/` | Backend (Maven multi-module: `domain`, `application`, `infrastructure`, `bootstrap`). One jar, two roles: `api` and `worker`. |
| `payment-mock/` | Independent Payment Mock (own Maven build), contract `payment-mock.openapi.v1.yaml`. |
| `platform/` | `infra-init` (table, indexes, queues), `local-idp` (local OIDC issuer with the five test identities) and verification scripts. |
| `docker-compose.yml` | DynamoDB Local, LocalStack (SQS), `infra-init`, `local-idp`, `payment-mock`, `ticketing-api` and `ticketing-worker` (same image, role by `TICKETING_ROLE`). Profile `load`: `load-token-generator` and `load-test`. |

## Requirements

- JDK 25 (`JAVA_HOME` set, or `java` on `PATH`)
- Docker with Compose v2
- `sh` and `curl` (Git Bash on Windows)

On Windows, clone into a short folder (the longest tracked path has 141 characters) or enable long paths first: `git config --global core.longpaths true`.

## Run locally

```sh
cp .env.example .env
# Set PAYMENT_MOCK_API_KEY to any value. If a host port is busy, change its *_HOST_PORT
# (e.g. PAYMENT_MOCK_HOST_PORT=18090, TICKETING_API_HOST_PORT, TICKETING_API_MANAGEMENT_HOST_PORT).
```

### Option A: everything in Docker Compose

```sh
docker compose up -d --wait     # builds the ticketing image (runs the unit test suite) and starts all services
docker compose down -v          # stops and removes everything (data is ephemeral)
```

The api is published on `127.0.0.1:8080` (API) and `127.0.0.1:8081` (management); the worker publishes no ports. The first `up` builds the images (about 2 minutes); later starts take about 30 seconds. Check the running environment with `sh platform/verify/environment.sh` (exit code = number of failed checks).

### Option B: api and worker as local JVMs (development)

`run-local.sh` starts only the Compose dependencies and runs the `api` and `worker` roles as local Java processes. Do not use it while option A is running: both use ports 8080 and 8081.

```sh
./run-local.sh start   # starts the Compose dependencies, builds the jar if missing, starts api (:8080) and worker (:8082) as local JVMs
./run-local.sh smoke   # creates an Event, buys a Ticket and waits until the Order is CONFIRMED
./run-local.sh stop    # stops api and worker
./run-local.sh down    # also removes the Compose environment (data is ephemeral)
```

Logs are written to `.run/api.log` and `.run/worker.log`.

### Calling the API by hand

```sh
# Access token for one of the identities: admin, customer-a, customer-b, admin-customer, no-groups
curl -s -X POST http://localhost:9000/token -d identity=admin

curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-event-0001-abcd" \
  -d '{"name":"Concert","venue":"Arena","startsAt":"2027-01-01T20:00:00Z","capacity":10,
       "inventory":{"sections":[{"code":"A","rows":[{"label":"1","seats":10}]}]}}'
```

Operations (contract in `ticketing/infrastructure/src/test/resources/contracts/ticketing.openapi.v2.yaml`):

| Method and path | Role |
|---|---|
| `POST /api/v1/events` | ADMIN |
| `GET /api/v1/events/{eventId}/provisioning` | ADMIN |
| `GET /api/v1/events` | ADMIN or CUSTOMER |
| `GET /api/v1/events/{eventId}/availability` | ADMIN or CUSTOMER |
| `POST /api/v1/orders` (`Idempotency-Key` header) | CUSTOMER |
| `GET /api/v1/orders/{orderId}` | owner CUSTOMER |

### Postman collection

`postman/ticketing.postman_collection.json` and the environment `postman/ticketing-local.postman_environment.json` cover tokens for the five identities, Event creation and provisioning, catalog and availability, purchase with idempotent replay, Order polling until `CONFIRMED`, error cases (401, 403, 400, 404, 409, 422) and the Payment Mock control API. Set `paymentMockUrl` and `paymentMockApiKey` to the values of your `.env` and run the folders in order with the Collection Runner. From the command line:

```sh
npx newman run postman/ticketing.postman_collection.json -e postman/ticketing-local.postman_environment.json \
  --env-var paymentMockUrl=http://localhost:18090 --env-var paymentMockApiKey=<PAYMENT_MOCK_API_KEY>
```

Health: `GET :8080/readyz`, `GET :8080/livez`. Metrics on the management port: `GET :8081/actuator/prometheus`.

## Build and tests

```sh
cd ticketing
./mvnw verify                  # unit, architecture, blocking detector and 90 % coverage gate (no Docker)
./mvnw verify -Pintegration    # adds the integration tests against DynamoDB Local and LocalStack (Docker)

cd ../payment-mock
./mvnw verify
```

## Configuration

The backend reads its configuration from environment variables (`TICKETING_ROLE=api|worker`, `TICKETING_DYNAMODB_TABLE`, `TICKETING_SQS_*_QUEUE_URL`, `TICKETING_SECURITY_*`, `TICKETING_PAYMENT_*`, …). `run-local.sh` shows the complete local set. AWS credentials are read through the standard SDK chain; the values in `.env.example` are fictitious and only valid against the local emulators.
