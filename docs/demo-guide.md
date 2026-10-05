# Guía de demostración para la reunión técnica

Guion para presentar la solución en unos 40 minutos, con el material que ya existe en el repositorio. Todas las cifras y resultados que se mencionan tienen su fuente; no hay respuestas ni salidas preparadas que no estén respaldadas por evidencia.

## 1. Antes de la reunión

| Paso | Comando o acción | Resultado esperado |
|---|---|---|
| 1. Levantar el entorno (la primera vez construye las imágenes, unos 2 min) | `docker compose up -d --wait` | exit 0 |
| 2. Comprobar el entorno | `bash platform/verify/environment.sh` | `RESULT: 65 passed, 0 failed` |
| 3. Importar la colección principal y su environment en Postman; escribir `paymentMockApiKey` desde `.env` | Ver [`collection-guide.md`](collection-guide.md#1-preparación) | — |
| 4. Ensayo: ejecutar la colección por defecto (00 a 08 y 99) | Runner o newman ([README §12](../README.md#12-colección-de-solicitudes)) | 0 fallos (unos 12 s) |
| 5. Dejar abiertos | README, [`diagrams.md`](diagrams.md), [`architecture.md`](architecture.md), una terminal en la raíz del repositorio | — |
| 6. Si el puerto 8090 está ocupado | `PAYMENT_MOCK_HOST_PORT=18090` en `.env` y en `paymentMockUrl` | — |

Los tokens duran una hora: si el ensayo fue mucho antes, vuelve a ejecutar la carpeta 00.

## 2. Orden de la presentación

| # | Bloque | Min. | Qué mostrar | Material | Idea clave |
|---:|---|---:|---|---|---|
| 1 | Problema y criterios | 2 | Sobreventa, duplicados y timeouts en picos; criterios de evaluación | [README §1](../README.md#1-propósito-y-alcance) | Responder rápido sin esperar el pago y no vender dos veces un asiento |
| 2 | Arquitectura | 5 | Contexto y contenedores; una imagen con dos roles; tabla, colas y mock | [`diagrams.md`](diagrams.md) §1 y §2, [README §3](../README.md#3-arquitectura-en-resumen) | Lo síncrono es corto; lo pesado va por colas |
| 3 | Entorno | 2 | `docker compose ps -a` y salida de `environment.sh` | [README §7 y §8](../README.md#7-inicio-rápido-con-docker-compose) | Mismo diseño local que el objetivo, sin secretos reales |
| 4 | Flujo feliz | 5 | Colección 00 a 04: 202 y sondeo hasta `ENABLED`; disponibilidad con cursor; 201 `CREATED` y sondeo hasta `CONFIRMED`; un único intento de pago en `API-108` | Colección; [`diagrams.md`](diagrams.md) §3 y §4 | Crear y comprar son asíncronos; la disponibilidad es informativa |
| 5 | Rechazo determinista | 3 | Carpeta 05: regla `DECLINE` antes de comprar; `REJECTED` con `PAYMENT_DECLINED`; Ticket liberado | Colección; `diagrams.md` §6 | El resultado se elige en el mock (AV-004), no con un campo de la compra |
| 6 | Idempotencia y Order activa | 3 | Carpeta 07: 422 con clave reutilizada, 409 `ACTIVE_ORDER_EXISTS`, repetición 200, repetición de un rechazo | Colección; `diagrams.md` §5 y §12 | Ningún rechazo persiste nada; repetir es seguro |
| 7 | Seguridad y propiedad | 3 | Carpeta 08: 401, 403 por rol, Order ajena igual que inexistente, 413 y 429 | Colección; [README §16](../README.md#16-seguridad-y-secretos) | Resource Server, propiedad por `sub`, límites contra abuso |
| 8 | Resiliencia (opcional) | 5 | Procedimiento 3 (cola pausada: 503 sin tocar el inventario, unos 20 s) o el 1 (`kill` del `worker`, unos 70 s) | [`resilience.md`](resilience.md) | Las dependencias caídas no dejan Orders a medias; estado `NOT_YET_VALIDATED_BY_QA` |
| 9 | Pruebas y cobertura | 3 | 770 pruebas y 99,49 % de cobertura; 129 de integración; pruebas de concurrencia | [README §13 y §15](../README.md#13-build-y-pruebas) | La concurrencia se prueba por invariantes, no con la colección |
| 10 | Decisiones y trade-offs | 5 | Tabla de decisiones con su alternativa descartada | [README §14](../README.md#14-decisiones-arquitectónicas-y-trade-offs), [`architecture.md`](architecture.md#2-decisiones) | Cada decisión tiene un coste aceptado |
| 11 | Limitaciones y producción | 3 | Limitaciones; topología AWS diseñada; Terraform no implementado | [README §19 y §20](../README.md#19-limitaciones-conocidas), [`architecture.md`](architecture.md#6-topología-aws-diseño) | Qué cambiaría en un entorno real |
| 12 | Preguntas | — | §4 | — | — |

## 3. Comandos de comprobación durante la reunión

```sh
docker compose ps -a                                              # 6 healthy + infra-init Exited (0)
curl -s http://localhost:8080/readyz                              # {"status":"UP"}
curl -s http://localhost:8081/actuator/prometheus | grep '^ticketing_circuit_state.* 1.0'
docker compose logs --tail 20 ticketing-worker                    # structured JSON logs
```

Para mostrar un flujo sin Postman, el bloque de [README §11](../README.md#11-api-y-ejemplos) recorre MF-001 a MF-004 con `curl` en menos de un segundo (0,8 s medidos en DOC-INC-006).

## 4. Preguntas previsibles y dónde está la respuesta

| Pregunta | Respuesta breve | Fuente |
|---|---|---|
| ¿Cómo evitan la sobreventa? | Cada Ticket se reserva con la condición `AVAILABLE` dentro de una sola transacción multi-partición; un solo ganador por Ticket | ADR-023; README §15; `DynamoDbMechanismIT` (INC-005) |
| ¿Por qué cada Ticket en su propia partición? | Para repartir las compras concurrentes de un Event grande entre particiones | ADR-022 |
| ¿Qué pasa si SQS falla justo después de reservar? | Publicación con presupuesto de 2 s; si falla, la Order queda `FAILED` y se liberan los Ticket; si el proceso cae, el barrido la republica a los 30 s; si la cola está caída, 503 antes de reservar | ADR-026, ADR-035; `diagrams.md` §7; `resilience.md` §4 |
| ¿Por qué no un outbox? | La respuesta síncrona no podría informar el fallo de encolado; queda como cambio productivo si la ventana residual no es aceptable | ADR-026; README §20 |
| ¿Cómo se garantiza el máximo de 10 minutos? | `expiresAt` es la autoridad; confirmar exige que no haya vencido; la expiración libera en 15 s como máximo | ADR-008, ADR-028 |
| ¿Y si el pago se aprueba tarde o su resultado es desconocido? | La Order no se reabre; se marca para reverso y se cancela el intento | ADR-025, ADR-030; colección 09.2 |
| ¿Cómo tratan los mensajes duplicados? | Identidad por `orderId` y estado de la Order; lease del PaymentAttempt; mismo `paymentAttemptId` ante el proveedor | ADR-027, ADR-029; `RoleComponentIT` (INC-010) |
| ¿Por qué la creación de Event responde 202? | Escribir 50.000 Ticket en la solicitud tardaría lo que su capacidad; se aprovisiona por lotes y se verifica | ADR-024 |
| ¿Cómo se protegen de reintentos maliciosos y bots? | Idempotencia obligatoria, una Order activa por cliente y Event, límites de cuerpo y de tasa; en AWS, WAF con control de bots | ADR-032, ADR-037 |
| ¿Dónde están los secretos? | Solo en `.env`, no versionado; ejemplo ficticio; imágenes y logs sin secretos; en AWS, gestor de secretos y roles | README §16; PLAT-INC-007 §5 |
| ¿Por qué un Payment Mock propio? | Resultados deterministas, idempotencia, fallos transitorios y cancelación anticipada bajo control, sin campos nuevos en la compra | ADR-030 |
| ¿Por qué no Virtual Threads? | Toda la E/S es reactiva y no bloqueante; el enunciado los pide solo "si aplica" | ADR-034 |
| ¿Cómo escalaría en AWS? | `api` por CPU y solicitudes; `worker` por mensajes pendientes y antigüedad; DynamoDB on-demand | ADR-037; `architecture.md` §6 |
| ¿Qué no está probado todavía? | Carga, extremo a extremo certificado y resiliencia por QA; Terraform no implementado | README §2; DOC-ISSUE-006 |
| ¿Qué cambiaría en producción? | Ver la tabla de cambios | README §20; arquitectura v2 §17 |

## 5. Plan de contingencia sin Docker

Si Docker no está disponible en la reunión, no se muestran salidas inventadas:

1. Recorrer la arquitectura con [`diagrams.md`](diagrams.md) (GitHub dibuja los diagramas) y la tabla de decisiones del README.
2. Mostrar los ejemplos documentados de [README §11](../README.md#11-api-y-ejemplos) y la [guía de la colección](collection-guide.md), indicando que los resultados citados se obtuvieron en la verificación documental (DOC-INC-003).
3. Si hay JDK 25, ejecutar en vivo las pruebas, que no necesitan Docker: `cd ticketing && ./mvnw verify` (unos 2 minutos, 770 pruebas y la puerta de cobertura).
4. Abrir la evidencia en `SPEC_REPO`: informes de Backend (INC-010), Payment Mock (PM-INC-006) y Platform (PLAT-INC-007) en [`implementation/`](https://github.com/jtorres1990/PruebaeTecnicaNequi/tree/main/implementation).

## 6. Riesgos durante la demostración

| Riesgo | Cómo evitarlo |
|---|---|
| Tokens caducados (1 h) o `local-idp` reiniciado | Volver a ejecutar la carpeta 00 |
| Payment Mock reiniciado durante un escenario | Volver a ejecutar la carpeta desde su reinicio del mock |
| Carpeta 07 ejecutada a mano, solicitud por solicitud | Ejecutarla con el Runner: la ventana de la regla `LATENCY` es de 2,5 s |
| Escenarios lentos (carpeta 09) | Mostrarlos solo si hay tiempo; el más largo tarda unos 2 minutos y medio |
| Procedimiento de resiliencia a medias | Restaurar con `docker compose unpause localstack` o `docker compose up -d --wait` |
