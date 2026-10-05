# Guía de la colección de solicitudes

La colección **principal de demostración** (`DEL-004`) es `postman/ticketing-demo.postman_collection.json`, con su environment `postman/ticketing-demo.postman_environment.json`. Recorre los flujos MF-001 a MF-005 contra el entorno local de Docker Compose y comprueba el estado HTTP y la forma de las respuestas según los contratos [`ticketing.openapi.v2.yaml`](../ticketing/infrastructure/src/test/resources/contracts/ticketing.openapi.v2.yaml) y [`payment-mock.openapi.v1.yaml`](../ticketing/infrastructure/src/test/resources/contracts/payment-mock.openapi.v1.yaml).

La colección **previa** (`postman/ticketing.postman_collection.json` y `postman/ticketing-local.postman_environment.json`), creada por el autor antes de esta documentación, se conserva sin cambios (§9).

La colección demuestra los flujos; no sustituye las pruebas automatizadas del backend ni la validación de QA (`NOT_YET_VALIDATED_BY_QA`).

## 1. Preparación

1. Levanta el entorno: `docker compose up -d --wait` (README §7).
2. En Postman, importa los dos archivos `ticketing-demo.*` y selecciona el environment **Ticketing demo (local)**.
3. En el environment, escribe en `paymentMockApiKey` (tipo *secret*, vacío en el archivo versionado) el valor de `PAYMENT_MOCK_API_KEY` de tu `.env`.
4. Si cambiaste puertos en `.env`, ajusta las URL del environment. En particular, `paymentMockUrl` debe usar tu `PAYMENT_MOCK_HOST_PORT`.

| Variable del environment | Valor versionado | Uso |
|---|---|---|
| `baseUrl` | `http://localhost:8080/api/v1` | `API-001` a `API-006` |
| `apiRootUrl` | `http://localhost:8080` | `/readyz` |
| `idpUrl` | `http://localhost:9000` | Tokens de `local-idp` |
| `paymentMockUrl` | `http://localhost:8090` | `API-103` a `API-111` |
| `paymentMockApiKey` | Vacío | Cabecera `X-Api-Key` del Payment Mock |
| `pollIntervalMs` | `1000` | Espera entre sondeos |
| `maxPolls` | `30` | Máximo de sondeos (o páginas) en la ejecución por defecto |
| `slowMaxPolls` | `300` | Máximo de sondeos en los escenarios lentos (unos 5 minutos) |

No guardes ni exportes un environment con la clave escrita. Los tokens se guardan en variables de la colección durante la ejecución y la carpeta 99 los vacía.

## 2. Ejecución por defecto

Con el **Collection Runner**: selecciona las carpetas **00 a 08 y 99**, en ese orden, y deja sin marcar la carpeta 09. Sin retardo entre solicitudes. Tarda unos 12 segundos.

Desde la línea de comandos, con newman 5.3.2 (Node.js 14 o superior) y desde la raíz del repositorio:

```sh
PAYMENT_MOCK_API_KEY=$(grep '^PAYMENT_MOCK_API_KEY=' .env | cut -d= -f2- | tr -d '\r')
newman run postman/ticketing-demo.postman_collection.json -e postman/ticketing-demo.postman_environment.json \
  --env-var paymentMockUrl=http://localhost:8090 --env-var "paymentMockApiKey=$PAYMENT_MOCK_API_KEY" \
  --folder "00 Entorno y salud" --folder "01 Control del Payment Mock" --folder "02 Aprovisionamiento de Event (MF-001)" \
  --folder "03 Catálogo y disponibilidad (MF-002)" --folder "04 Compra confirmada (MF-003, MF-004)" \
  --folder "05 Pago rechazado (AC-020)" --folder "06 Comportamientos de fallo" --folder "07 Idempotencia y Order activa" \
  --folder "08 Seguridad y propiedad" --folder "99 Limpieza"
```

Usa tu `PAYMENT_MOCK_HOST_PORT` en `paymentMockUrl`. La clave se lee de `.env` sin mostrarla.

Resultado verificado (2026-10-05): en DOC-INC-003, dos ejecuciones seguidas sobre el mismo entorno, 95 solicitudes ejecutadas (82 distintas más las repeticiones de sondeo y del límite de tasa) y 243 aserciones, 0 fallos en ambas; en DOC-INC-006, tras `down -v` y `up -d --wait`, 96 y 244, 0 fallos. Los conteos varían ligeramente según cuántos sondeos hagan falta.

## 3. Cómo está construida

- **Aislamiento.** La primera solicitud crea un `runId`. Con él, cada ejecución crea su propio Event, sus claves de idempotencia, sus sujetos `CUSTOMER` (`demo-<runId>-<escenario>`) y sus reglas del Payment Mock (por `customerRef`, que es el `sub` del JWT del comprador). No depende de datos de ejecuciones anteriores.
- **Límite de compras.** `API-004` admite 10 solicitudes por 10 s y sujeto (ADR-032). Cada escenario usa un sujeto propio; solo la solicitud 08.19 agota su límite a propósito.
- **Payment Mock configurado antes de cada escenario** (AV-004): reinicio (`API-110`) y regla explícita (`API-104`). El resultado del pago nunca se elige con un campo de la compra.
- **Sondeo acotado.** Las solicitudes de sondeo se repiten (`postman.setNextRequest`) cada `pollIntervalMs` hasta un estado final o hasta `maxPolls`. Si se agota el límite, la aserción muestra el estado observado y el número de sondeos.
- **Síncrono frente a asíncrono.** La compra se comprueba en dos pasos: 201 `CREATED` inmediato y, después, el estado final por `API-005`.
- **Forma del contrato.** Se comprueban campos obligatorios, enumerados, `Location`, `Idempotency-Replayed`, Problem Details (`type`, `title`, `status`, `code`, `traceId`) y que la Order no expone atributos técnicos.
- **Sin campos inventados.** Los cuerpos siguen los esquemas (`additionalProperties: false`). Las solicitudes inválidas a propósito lo indican en su descripción. El cuerpo de más de 256 KB es una compra válida rellenada con espacios.
- **Scripts.** Legibles, sin `eval`, sin descargas y sin hosts distintos de las variables del environment.
- **Nombres.** `NN.MM API-xxx Título` en español; cada descripción termina con una línea `EN:` en inglés (DOC-IV-003 b).

## 4. Carpetas

| Carpeta | Qué demuestra | IDs | Resultado esperado |
|---|---|---|---|
| 00 Entorno y salud | `runId`, salud del mock y del `api`, tokens de las cinco identidades | `API-111` | 200 en todo |
| 01 Control del Payment Mock | Mock en estado conocido | `API-110`, `API-105`, `API-107`, `API-103` | 204, 204, 200, `[]` |
| 02 Aprovisionamiento de Event | MF-001: creación asíncrona y sondeo; idempotencia de la creación; validaciones | `API-001`, `API-006`; AC-001, AC-017, AC-028, AC-033, AC-041, AC-042 | 202 `PROVISIONING` → `ENABLED` con 27 Ticket (2 de cortesía); 200 repetición; 422; 403; 400; 400 |
| 03 Catálogo y disponibilidad | MF-002: listado con `soldOut`; página, cursor y filtro; parámetros inválidos | `API-002`, `API-003`; AC-002, AC-010, AC-048 | `availableCount` 25; páginas sin repetidos; 400; 400; 404 |
| 04 Compra confirmada | MF-003 y MF-004: compra sin esperar el pago, repetición idempotente, sondeo, un único intento de pago, nueva compra tras la Order final | `API-004`, `API-005`, `API-108`, `API-003`; AC-003 a AC-006, AC-019, AC-024, AC-045 | 201 `CREATED` → `CONFIRMED`; 1 invocación `APPROVED` |
| 05 Pago rechazado | Rechazo determinista por regla y liberación del Ticket | `API-110`, `API-104`, `API-103`, `API-004`, `API-005`, `API-108`, `API-106`; AC-020 | `REJECTED` con `PAYMENT_DECLINED`; `RULE_DECLINED`; Ticket disponible |
| 06 Comportamientos de fallo | Error definitivo sin reverso; fallo transitorio corto con el mismo intento | `API-104`, `API-005`, `API-108`, `API-109`; AC-021, AC-023, AC-024 | `FAILED` con `PROCESSING_FAILED` y `API-109` 404; `CONFIRMED` con 3 invocaciones |
| 07 Idempotencia y Order activa | Clave reutilizada, una Order activa por cliente y Event, repetición, repetición de un rechazo | `API-104`, `API-004`, `API-005`; AC-036, AC-043, AC-045 | 201; 422; 409 `ACTIVE_ORDER_EXISTS`; 200 repetición; `CONFIRMED`; 201 |
| 08 Seguridad y propiedad | 401, 403 por rol, Order ajena igual que inexistente y malformada, validaciones, conflictos, 413 y 429 | Todas las de `ticketing`; AC-016, AC-027, AC-028, AC-032 | Ver la descripción de cada solicitud |
| 09 Escenarios lentos | Fuera de la ejecución por defecto (§5) | MF-005; AC-021, AC-023, AC-024, AC-037, AC-038 | Ver §5 |
| 99 Limpieza | Reinicio del mock y borrado de tokens | `API-110` | 204 |

La carpeta 07 usa una regla `LATENCY` de 2,5 s para que la primera Order siga `CREATED` mientras se envían las solicitudes siguientes. Ejecútala completa con el Runner o con newman; enviando las solicitudes a mano, de una en una, la Order puede terminar antes de la 07.06 y esa solicitud recibiría 201 en lugar de 409.

## 5. Escenarios lentos (carpeta 09)

Tardan de 30 s a varios minutos por diseño (lease de 45 s, backoff por visibilidad, 5 recepciones, proceso de reversos cada 10 s). Se ejecutan aparte (DOC-IV-005 a), después de las carpetas 00 a 02, que crean el `runId`, los tokens y el Event, y antes de la 99.

| Subcarpeta | Duración medida | Qué demuestra | Resultado verificado |
|---|---|---|---|
| 09.1 Fallo transitorio con reentrega | 47 s | 4 respuestas 503: la primera recepción agota sus 3 llamadas, el mensaje vuelve a la cola, una recepción se pospone hasta el fin del lease y la siguiente reclama el mismo PaymentAttempt (ADR-029) | `CONFIRMED`; 5 invocaciones del mismo `paymentAttemptId` |
| 09.2 Reverso de pago no aplicado (MF-005) | 117 s hasta `FAILED` y 21 s más hasta la cancelación | Fallos sostenidos: tras la quinta recepción, `FAILED` con resultado desconocido y marca de reverso; el proceso de reversos cancela el intento (FG-003, ADR-025, ADR-030) | `FAILED` con `PROCESSING_FAILED`; `API-109`: 1 cancelación `REGISTERED_BEFORE_CHARGE`; Ticket liberado |
| 09.3 Event pasado | Unos 30 s | Compra sobre un Event cuyo inicio pasó; disponibilidad informativa; listado | 409 `EVENT_NOT_ON_SALE`; `API-003` 200; ausente del listado |

Con newman, sustituye la lista de carpetas de §2 por:

```sh
--folder "00 Entorno y salud" --folder "01 Control del Payment Mock" --folder "02 Aprovisionamiento de Event (MF-001)" \
  --folder "09 Escenarios lentos (fuera de la ejecución por defecto)" --folder "99 Limpieza"
```

Para un solo escenario, usa el nombre de su subcarpeta (por ejemplo `--folder "09.3 Event pasado"`) en lugar de la carpeta 09.

Durante 09.2 el circuito del Payment Mock puede abrirse y la expiración sigue funcionando; el tiempo total depende de ello. El sondeo está acotado por `slowMaxPolls` (300 sondeos, unos 5 minutos).

## 6. Lo que la colección no demuestra

| Comportamiento | Por qué | Dónde está la evidencia |
|---|---|---|
| Cero sobreventa bajo concurrencia (AC-007, AC-029) | Requiere solicitudes simultáneas y comprobar invariantes | Pruebas deterministas del backend (ADR-038); carga pendiente de QA |
| 503 con la cola de mensajes caída (AC-046) | Requiere pausar LocalStack | Escenario 3 de [`resilience.md`](resilience.md#4-procedimiento-3-detener-localstack-cola-de-mensajes) |
| Expiración tras 10 minutos (AC-008) | Duración real de la Reservation | `RoleComponentIT.expirationReleasesTheTickets` (INC-010) con reloj inyectado |
| Aprobación tardía sobre una Order expirada (AC-034, AC-050) | La latencia máxima del mock (60 s) es menor que la Reservation | Pruebas del backend (INC-009, INC-010) |
| Reverso de un pago aprobado (`REVERSED`) | El mock no puede aprobar después de que el `worker` haya agotado sus recepciones sin respuesta | Pruebas del Payment Mock (PM-INC-004) y del backend (INC-010) |
| `API-101` y `API-102` llamadas a mano | El flujo público es `API-004`; el `worker` llama al mock | Pruebas de contrato del adaptador (INC-007) y del mock (PM-INC-003/004) |

## 7. Problemas conocidos

- El esquema `OutcomeRule` del contrato del mock (`allOf` con `additionalProperties: false`) no lo satisface ningún objeto con un validador estricto; el mock aplica la tolerancia aprobada PM-IV-016. La colección comprueba `ruleId`, `match` y `behaviour`, que es la intención del contrato (DOC-ISSUE-001).
- Si el Payment Mock se reinicia durante un escenario, pierde reglas y registros; vuelve a ejecutar la carpeta desde su reinicio.
- Reiniciar `local-idp` invalida los tokens: vuelve a ejecutar la carpeta 00.

## 8. Correspondencia con las operaciones

| Operación | Solicitudes |
|---|---|
| `API-001` | 02.01 a 02.03, 02.06, 02.07, 08.03, 09.3.1 |
| `API-002` | 03.01, 08.01, 08.02, 09.3.6 |
| `API-003` | 03.02 a 03.07, 04.06, 05.08, 09.2.8, 09.3.2, 09.3.5 |
| `API-004` | 04.02, 04.03, 04.07, 05.05, 06.04, 06.10, 07.04 a 07.07, 07.09, 08.04, 08.05, 08.10 a 08.17, 08.19, 09.1.4, 09.2.4, 09.3.4 |
| `API-005` | 04.04, 05.06, 06.05, 06.11, 07.08, 08.07 a 08.09, 09.1.5, 09.2.5 |
| `API-006` | 02.04, 02.05 |
| `API-103`, `API-104`, `API-105`, `API-106`, `API-107` | 01.04, 05.04; 05.03, 06.03, 06.09, 07.03, 09.1.3, 09.2.3; 01.02; 05.09; 01.03 |
| `API-108`, `API-109` | 04.05, 05.07, 06.06, 06.12, 09.1.6, 09.2.7; 06.07, 09.2.6 |
| `API-110`, `API-111` | 01.01, 05.02, 06.02, 06.08, 07.02, 09.1.2, 09.2.2, 09.2.9, 99.01; 00.01 |
| Fuera del contrato | 00.02 (`/readyz`), tokens de `local-idp` |

## 9. Colección previa

`postman/ticketing.postman_collection.json` (30 solicitudes) y `postman/ticketing-local.postman_environment.json` se conservan **sin cambios** por decisión del autor (DOC-IV-004 b). Cubren el flujo feliz y varios errores, y funcionan con newman (PLAT-INC-006/007). Diferencias con la colección principal, corregidas solo en esta:

| Colección previa | Colección principal |
|---|---|
| Algunas descripciones citan IDs de operación desplazados (por ejemplo, el aprovisionamiento como `API-002`) | IDs según `x-id` del OpenAPI v2 |
| El sondeo de la Order no considera `REJECTED` estado final; un rechazo con `declinePercentage=100` se describe como `FAILED` | Estados finales completos; rechazo por regla explícita con `REJECTED` y `PAYMENT_DECLINED` |
| El environment versiona un valor de `paymentMockApiKey` | Clave vacía en el archivo versionado |
| Los tokens quedan en variables de la colección | La carpeta 99 los borra |
| `customer-a` hace todas las compras | Un sujeto `CUSTOMER` por escenario, por el límite de 10 compras por 10 s |
