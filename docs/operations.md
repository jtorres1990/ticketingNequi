# Operación del entorno local

Detalle operativo del entorno Docker Compose: puertos, variables, comandos de ciclo de vida, verificadores, salud y métricas, modo con JVM locales y alternativas. El resumen está en el [README](../README.md); aquí está lo que no cabe allí.

Todos los comandos se ejecutan desde la raíz del repositorio. Los bloques `sh` funcionan en Git Bash (Windows), Linux y macOS; los bloques `powershell` solo se muestran donde la sintaxis difiere. Cada comando lleva su estado de verificación (fecha 2026-10-05, Docker 29.8.0, Compose v5.5.1, Windows 10 con Git Bash 5.2.12).

## 1. Servicios, puertos y URLs

Todos los puertos de host se publican solo en `127.0.0.1`. Los puertos de contenedor son fijos; el lado del host se cambia en `.env`.

| Servicio | URL desde el host (valor por defecto) | Variable del puerto de host | Propósito |
|---|---|---|---|
| `ticketing-api` | `http://localhost:8080/api/v1` | `TICKETING_API_HOST_PORT` | API pública (`API-001` a `API-006`), `/readyz`, `/livez` |
| `ticketing-api` (gestión) | `http://localhost:8081/actuator/...` | `TICKETING_API_MANAGEMENT_HOST_PORT` | `health`, `info`, `metrics`, `prometheus`; solo local |
| `ticketing-worker` | Sin puertos de host | — | Consumidores y procesos periódicos; solo accesible desde la red de Compose (`ticketing-worker:8080` y `:8081`) |
| `payment-mock` | `http://localhost:8090` | `PAYMENT_MOCK_HOST_PORT` | API de control y salud del Payment Mock |
| `local-idp` | `http://localhost:9000` | `LOCAL_IDP_HOST_PORT` | Emisión de tokens, discovery y JWKS |
| `dynamodb-local` | `http://localhost:8000` | `DYNAMODB_HOST_PORT` | Inspección de la tabla |
| `localstack` | `http://localhost:4566` | `LOCALSTACK_HOST_PORT` | Inspección de las colas SQS |
| `infra-init` | — | — | Un solo uso: crea tabla, índices, TTL y colas; termina con código 0 |

Si un puerto está ocupado (por ejemplo el 8090 en algunos equipos Windows), cambia solo su variable en `.env` y usa ese puerto en las URL de este documento, del README y del environment de Postman.

## 2. Variables de `.env`

`.env` no se versiona (`.gitignore`); se crea copiando `.env.example`, que solo contiene valores ficticios válidos contra los emuladores. Compose falla con un mensaje explícito si falta una variable obligatoria.

| Variable | Propósito | Obligatoria | Valor en `.env.example` |
|---|---|---|---|
| `AWS_REGION` | Región usada por los emuladores y en las URL de las colas | Sí | `us-east-1` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Credenciales **ficticias**, solo para DynamoDB Local y LocalStack. DynamoDB Local 3.x solo acepta letras y dígitos en la clave | Sí | Valores `fictitious…` |
| `PAYMENT_MOCK_API_KEY` | Secreto compartido por `payment-mock` y `ticketing-worker` (`X-Api-Key`). Pon cualquier valor propio | Sí | Valor ficticio que debes cambiar |
| `TICKETING_TABLE_NAME` | Nombre de la tabla creada por `infra-init` | No | `ticketing` |
| `ORDERS_QUEUE_NAME`, `ORDERS_DLQ_NAME`, `PROVISIONING_QUEUE_NAME`, `PROVISIONING_DLQ_NAME` | Nombres de las cuatro colas | No | `ticketing-orders`, `ticketing-orders-dlq`, `ticketing-event-provisioning`, `ticketing-event-provisioning-dlq` |
| `DYNAMODB_HOST_PORT`, `LOCALSTACK_HOST_PORT`, `LOCAL_IDP_HOST_PORT`, `PAYMENT_MOCK_HOST_PORT`, `TICKETING_API_HOST_PORT`, `TICKETING_API_MANAGEMENT_HOST_PORT` | Puertos de host (§1) | No | `8000`, `4566`, `9000`, `8090`, `8080`, `8081` |
| `LOCAL_IDP_ISSUER` | Valor del claim `iss`; debe coincidir con el emisor esperado por el `api` | No | `http://local-idp:9000` |
| `LOCAL_IDP_CLIENT_ID` | Claim `client_id` emitido y aceptado | No | `ticketing-local-client` |
| `LOCAL_IDP_DEFAULT_TTL_SECONDS`, `LOCAL_IDP_MAX_TTL_SECONDS` | Vigencia por defecto y máxima de los tokens | No | `3600`, `86400` |
| `TICKETING_WORKER_ID` | Identificador del `worker` en leases y auditoría; vacío = `worker-<aleatorio>` | No (comentada) | — |
| `LOAD_TOKEN_COUNT`, `LOAD_TOKEN_TTL_SECONDS` | Lote de tokens del perfil `load` | No | `1200`, `7200` |
| `INFRA_INIT_WAIT_TIMEOUT_SECONDS` | Espera máxima de `infra-init` por los emuladores (solo en `docker-compose.yml`) | No | No figura; por defecto `60` |

## 3. Variables de la aplicación

Compose las fija a partir de `.env`; no hace falta definirlas para usar el entorno. Son los nombres literales del handoff del backend (INC-010 §6.5.3) y sirven para ejecutar el jar fuera de Compose.

| Variable | Rol | Valor en Compose |
|---|---|---|
| `TICKETING_ROLE` | Ambos (obligatoria) | `api` o `worker` |
| `TICKETING_DYNAMODB_TABLE` | Ambos | `${TICKETING_TABLE_NAME}` |
| `TICKETING_DYNAMODB_ENDPOINT`, `TICKETING_SQS_ENDPOINT` | Ambos (vacías en AWS) | `http://dynamodb-local:8000`, `http://localstack:4566` |
| `TICKETING_SQS_ORDERS_QUEUE_URL`, `TICKETING_SQS_PROVISIONING_QUEUE_URL` | Ambos | `http://localstack:4566/queue/<región>/000000000000/<cola>` |
| `TICKETING_SECURITY_ISSUER`, `TICKETING_SECURITY_JWK_SET_URI`, `TICKETING_SECURITY_ALLOWED_CLIENT_IDS` | `api` | `http://local-idp:9000`, `http://local-idp:9000/.well-known/jwks.json`, `ticketing-local-client` |
| `TICKETING_PAYMENT_BASE_URL`, `TICKETING_PAYMENT_API_KEY` | `worker` | `http://payment-mock:8090`, `${PAYMENT_MOCK_API_KEY}` |
| `TICKETING_WORKER_ID` | `worker` (opcional) | `${TICKETING_WORKER_ID}` |
| `TICKETING_SERVER_PORT`, `TICKETING_MANAGEMENT_PORT` | Ambos (opcionales) | `8080`, `8081` por defecto |
| `TICKETING_TRACING_EXPORT_ENABLED`, `TICKETING_TRACING_OTLP_ENDPOINT` | Ambos (opcionales) | Exportación OTLP desactivada |
| `TICKETING_LOG_FORMAT` | Ambos (opcional) | `ecs` (JSON); vacío = texto |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Ambos | Los de `.env` (cadena estándar del SDK) |

Los demás valores aprobados (plazos, reintentos, circuit breakers, shards) tienen su valor por defecto en el código y se pueden sobrescribir con `TICKETING_<GRUPO>_<PROPIEDAD>` (INC-010 §6.5.3). Ningún valor por defecto contiene secretos.

## 4. Ciclo de vida

| Acción | Comando | Efecto | Verificación |
|---|---|---|---|
| Arrancar todo | `docker compose up -d --wait` | Construye las imágenes que falten (el build de `ticketing` y de `payment-mock` ejecuta sus pruebas), arranca en orden y espera a que todo esté sano | Verificado: 24,8 s con las imágenes ya construidas; unos 2 min desde un clon limpio (PLAT-INC-007) |
| Estado | `docker compose ps -a` | 6 servicios `healthy` e `infra-init` `Exited (0)` | Verificado |
| Logs | `docker compose logs -f ticketing-api ticketing-worker` | Logs JSON (ECS) de ambos roles | Verificado |
| Parar sin borrar contenedores | `docker compose stop` | Apagado ordenado (hasta 35 s por fase). Los emuladores son efímeros: con `docker compose up -d --wait` vuelven vacíos e `infra-init` recrea tabla y colas | Verificado: parada en 7,2 s; nuevo `up -d --wait` en 28 s, sin Events previos |
| Parar y eliminar contenedores y red | `docker compose down` | Los datos de los emuladores se pierden (no hay volúmenes de datos) | Verificado en PLAT-INC-006 |
| Eliminar también el volumen del perfil `load` | `docker compose down -v` | Además borra el volumen `load-tokens` | Verificado |
| Reconstruir sin caché | `docker compose build --no-cache` | Vuelve a ejecutar las pruebas de ambos builds (unos 2 min 15 s) | Verificado en PLAT-INC-007 |
| Perfil de carga | `docker compose --profile load up -d` | Genera 1.200 tokens `CUSTOMER` en el volumen `load-tokens`; `load-test` termina con código 1 a propósito hasta que QA entregue su arnés | Verificado en PLAT-INC-006/007 |

`docker compose down -v` solo afecta a este proyecto (`name: ticketing-platform`). No uses limpiezas globales de Docker para este entorno.

## 5. Verificadores

| Verificador | Comando | Comprueba | Resultado observado |
|---|---|---|---|
| Entorno | `bash platform/verify/environment.sh` | Topología, salud, puertos, endurecimiento, imágenes fijadas y ausencia de secretos. Código de salida = número de fallos | 65 correctas, 0 fallos |
| Recursos | `MSYS_NO_PATHCONV=1 docker compose run --rm --no-deps -v ./platform/verify:/verify:ro --entrypoint sh infra-init /verify/resources.sh` | Tabla, `GSI1` a `GSI4`, TTL y las cuatro colas con sus atributos | 38 correctas, 0 fallos |
| Identidad | `bash platform/verify/identity.sh --skip-restart` | Discovery, JWKS, cinco identidades, sujeto arbitrario, rechazos y lote de 1.200 tokens | 38 correctas, 0 fallos (24 s). Deja el contenedor `load-token-generator` y el volumen `load-tokens` del perfil `load` |
| `infra-init` negativo | `bash platform/verify/infra-init-negative.sh` | Recursos incompatibles en un proyecto aislado (`ticketing-platform-negtest`) | No ejecutado en esta verificación (PLAT-INC-002) |

`MSYS_NO_PATHCONV=1` solo hace falta en Git Bash: sin él, Git Bash convierte `/verify/resources.sh` en una ruta de Windows y el comando falla con `No such file or directory`. En PowerShell, Linux y macOS se omite.

Sin `--skip-restart`, `identity.sh` reinicia `local-idp` y cambia su clave de firma: los tokens emitidos antes dejan de ser válidos.

Después de `identity.sh`, `environment.sh` informa 2 fallos ("load profile containers" y "project volumes") porque quedan los restos del perfil `load`. Para retirarlos sin tocar el resto del entorno:

```sh
docker compose --profile load rm -s -f load-token-generator
docker volume rm ticketing-platform_load-tokens
```

Verificado: después, `environment.sh` vuelve a 65 correctas y 0 fallos.

## 6. Salud, métricas, logs y trazas

| Señal | Dónde | Resultado observado |
|---|---|---|
| Preparación y vida del `api` | `GET http://localhost:8080/readyz`, `/livez` | 200 `{"status":"UP"}` |
| Salud y métricas del `api` | `GET http://localhost:8081/actuator/health`, `/actuator/prometheus` | 200. En el puerto 8080, `/actuator/**` responde 401 |
| Salud del Payment Mock | `GET http://localhost:8090/health` (sin API key) | 200 |
| Salud del emisor local | `GET http://localhost:9000/.well-known/openid-configuration`, `/.well-known/jwks.json` | 200 |
| Métricas del `worker` | Solo desde la red de Compose (`ticketing-worker:8081/actuator/prometheus`) | Ver [`resilience.md`](resilience.md#1-preparación-común) |

Métricas principales de la aplicación (catálogo completo en INC-010 §6.3): `ticketing_reservations_total`, `ticketing_orders_terminal_total{status,cause}`, `ticketing_orders_expiration_lag_seconds`, `ticketing_payment_reversals_total{outcome}`, `ticketing_messages_processed_total`, `ticketing_provisioning_total`, `ticketing_circuit_state{circuit,state}`, `ticketing_sqs_failures_total`, `ticketing_dynamodb_requests_seconds`, `ticketing_http_rate_limited_total`. El estado del circuito `sqs-publication` se ve en el `api`; el del circuito `payment-mock` solo en el `worker`.

Los logs son JSON con formato ECS: cada registro lleva `event` y campos como `orderId`, `eventId` y `correlationId`; nunca tokens ni la API key. Las trazas se propagan del HTTP a los mensajes SQS (`traceparent`); la exportación OTLP está desactivada por defecto.

## 7. Recuperación tras reiniciar un emulador

`dynamodb-local` y `localstack` son efímeros. Un `docker compose restart` de cualquiera de ellos borra la tabla o las colas. Para recrearlas sin tocar `api` ni `worker`:

```sh
docker compose up -d --wait infra-init && docker compose wait infra-init
```

Verificado en PLAT-INC-007 (unos 13 s). `docker compose pause localstack` / `unpause localstack` conserva las colas.

## 8. Modo desarrollo con JVM locales (`run-local.sh`)

Opción para depurar el backend: levanta solo las dependencias de Compose y ejecuta los roles como procesos Java del host.

| Aspecto | Comportamiento real del script |
|---|---|
| Requisitos | JDK 25 (`JAVA_HOME` o `java` en el `PATH`), Docker, `curl`, `.env` |
| `start` | `docker compose up -d --wait` de `dynamodb-local`, `localstack`, `infra-init`, `local-idp` y `payment-mock`; espera a que `infra-init` termine con código 0; construye el jar si falta (`./mvnw -DskipTests package`); arranca `api` en `:8080` (gestión `:8081`) y `worker` en `:8082` (gestión `:8083`) |
| `smoke` | Crea un Event de 10 asientos, espera `ENABLED`, compra `A-1-1` con `customer-a` y espera un estado final de la Order; termina con éxito solo si es `CONFIRMED` |
| `stop` | Detiene las dos JVM; Compose sigue levantado |
| `down` | `stop` y además `docker compose down -v` |
| Logs y PID | `.run/api.log`, `.run/worker.log`, `.run/*.pid` (ignorados por Git) |
| Puertos | Usa `API_PORT`, `API_MANAGEMENT_PORT`, `WORKER_PORT` y `WORKER_MANAGEMENT_PORT` del entorno (por defecto 8080, 8081, 8082, 8083). Estas variables no están en `.env.example` y no siguen `TICKETING_API_HOST_PORT` (DOC-ISSUE-008) |

No combines `run-local.sh start` con el arranque completo de Compose: ambos usan 8080/8081 y consumirían las mismas colas. Detén antes `ticketing-api` y `ticketing-worker` de Compose.

## 9. Alternativa de LocalStack con token (ADR-036)

Estado: `Designed, not implemented`. Ejemplo conceptual, **no verificado** y nunca ejecutado con un token real.

El entorno soportado y verificado es `localstack/localstack:4.14.0` fijado por digest y **sin token**. ADR-036 prevé como alternativa la versión actual de LocalStack con un token gratuito en un archivo no versionado. `docker-compose.yml` no ofrece hoy un mecanismo para ello (sin variable de imagen ni de `LOCALSTACK_AUTH_TOKEN`; DOC-ISSUE-007 para Platform). La forma prevista sería un archivo de override local que no se versiona:

```yaml
# compose.localstack-token.yml - conceptual example, NOT verified, do not commit
services:
  localstack:
    image: localstack/localstack:<current-tag>@sha256:<digest>
    environment:
      LOCALSTACK_AUTH_TOKEN: ${LOCALSTACK_AUTH_TOKEN:?set LOCALSTACK_AUTH_TOKEN in .env}
```

Se usaría con `docker compose -f docker-compose.yml -f compose.localstack-token.yml up -d --wait`, con el token solo en `.env`. Antes de usarlo habría que repetir las comprobaciones de LocalStack del entorno local (SQS Standard, redrive, contador de recepciones, cambio de visibilidad).

## 10. Solución de problemas en detalle

Solo fallos observados en los informes de los agentes o durante la verificación de esta documentación.

| Síntoma | Causa | Solución | Origen |
|---|---|---|---|
| `Filename too long` al clonar en Windows | La ruta versionada más larga mide 141 caracteres | Clonar en una ruta corta o `git config --global core.longpaths true` | PLAT-INC-007 §3; DOC-ISSUE-002 |
| Puerto 8090 ocupado | Otro proceso del host usa el puerto | `PAYMENT_MOCK_HOST_PORT=18090` en `.env` y usar ese puerto en las URL | PM-INC-006 §7.1 |
| `set PAYMENT_MOCK_API_KEY in .env` al ejecutar Compose | Falta la variable obligatoria | Copiar `.env.example` a `.env` y fijar la clave | `docker-compose.yml` |
| `api` y `worker` no arrancan; `infra-init` termina con código distinto de 0 | Configuración de recursos inválida | `docker compose logs infra-init`; corregir `.env` y repetir `up -d --wait` | PLAT-INC-006 §4.5 |
| Tabla o colas inexistentes tras reiniciar un emulador | Emuladores efímeros | §7 | PLAT-INC-007 §3 |
| Tokens rechazados (401) tras reiniciar `local-idp` | La clave de firma cambia en cada arranque | Pedir tokens nuevos | PLAT-INC-003 §8 |
| `resources.sh: No such file or directory` en Git Bash | Conversión de rutas de MSYS | Prefijo `MSYS_NO_PATHCONV=1` (§5) | Verificación de DOC-INC-002 |
| `environment.sh` falla en "load profile containers" y "project volumes" | Restos del perfil `load` tras `identity.sh` | Limpieza de §5 | Verificación de DOC-INC-002 |
| En PowerShell 5.1, `curl -X ...` falla | `curl` es un alias de `Invoke-WebRequest` | Usar `curl.exe` o los cmdlets `Invoke-RestMethod` | Verificación de DOC-INC-002 |
| `run-local.sh` y Compose completo a la vez: conflicto de puertos | Ambos usan 8080/8081 | §8 | PLAT-INC-006 §8 |
| La compra responde 503 `SERVICE_UNAVAILABLE` con `Retry-After` | Circuito de publicación en SQS abierto (cola caída o pausada) | Comprobar `localstack`; tras recuperarla, repetir con la misma `Idempotency-Key` después de `Retry-After` | Verificación de DOC-INC-005 ([`resilience.md`](resilience.md#4-procedimiento-3-detener-localstack-cola-de-mensajes)) |
| Las Orders se quedan en `CREATED` | Payment Mock caído: circuito abierto y consumo en pausa | `docker compose up -d --wait payment-mock` | Verificación de DOC-INC-005 ([`resilience.md`](resilience.md#3-procedimiento-2-detener-payment-mock)) |
| `localstack` aparece `unhealthy` tras `pause`/`unpause` | La sonda tarda unos segundos en volver | Esperar a `healthy` con `docker compose ps` | Verificación de DOC-INC-005; PLAT-INC-007 §3 |
