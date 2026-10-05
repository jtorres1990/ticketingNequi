# Arquitectura y decisiones

Guía de la arquitectura implementada: qué problema resuelve cada decisión, qué se eligió, qué alternativa se descartó y qué consecuencia se aceptó. Es un resumen autosuficiente; la fuente normativa son los artefactos aprobados de `SPEC_REPO`, enlazados en cada sección:

- Arquitectura v2: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md)
- Registro de decisiones (estado autoritativo de cada ADR): [`ticketing.adr-registry.v1.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ticketing.adr-registry.v1.md)
- Modelo de datos v2, mensajería v2 y topología AWS v2: [`ticketing.data-model.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.data-model.v2.md), [`ticketing.messaging.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.messaging.v2.md), [`ticketing.aws-target.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.aws-target.v2.md)
- Especificación funcional v5: [`ticketing.feature-spec.v5.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/feature-spec/ticketing.feature-spec.v5.md)

Las decisiones vigentes son ADR-003, ADR-008 y ADR-022 a ADR-040 (`ACCEPTED`). ADR-001 a ADR-021, salvo ADR-003 y ADR-008, están reemplazadas (`SUPERSEDED`) y solo tienen valor histórico; aquí no se usan. Diagramas en [`diagrams.md`](diagrams.md).

## 1. Vista general

Una imagen de `ticketing` con dos roles:

- **`api`**: las seis operaciones HTTP (`API-001` a `API-006`). Crear un Event lo deja en `PROVISIONING` y encola su aprovisionamiento (202). Iniciar una compra reserva de forma atómica entre 1 y 10 Ticket, crea la Order con su Reservation y el bloqueo de Order activa, publica el mensaje y responde con la Order sin esperar al pago.
- **`worker`**: dos consumidores (Orders y aprovisionamiento) y cuatro procesos periódicos con planificación independiente: expiración, barrido de republicación, reversos de pago y limpieza del aprovisionamiento.

Cuatro ideas sostienen el diseño (arquitectura v2 §1):

1. **Cada transición de negocio es una única escritura transaccional condicional** que incluye la Order, todos sus Ticket, la auditoría y, cuando aplica, el bloqueo de Order activa y la marca de reverso.
2. **La Order es el guardián**: toda transición final exige que la Order siga en `CREATED`; pago, rechazo, fallo y expiración compiten y exactamente uno gana.
3. **Nada queda colgado**: el barrido republica Orders sin mensaje, la expiración cierra cualquier Order no resuelta, la detección de estancados cierra aprovisionamientos sin progreso y el proceso de reversos devuelve pagos sin compra.
4. **La disponibilidad se deriva de los Ticket**, paginada y con un recuento cacheado 1 s, sin contadores persistidos.

## 2. Decisiones

### 2.1 Clean Architecture y Payment Mock independiente

| | |
|---|---|
| Problema | Separar dominio, casos de uso e infraestructura de forma verificable (`TC-007`) y tener un proveedor de pagos simulado sin acoplarlo al backend |
| Decisión | Build Maven multi-módulo (`domain`, `application`, `infrastructure`, `bootstrap`) donde el compilador impide las dependencias prohibidas; `domain` y `application` sin tipos de Spring, del SDK de AWS ni de HTTP. `payment-mock` es un proyecto aparte, con su build, imagen y pruebas, sin código compartido |
| Alternativa descartada | Un módulo con paquetes por capa (la separación dependería solo de una prueba) y el mock como módulo con DTOs compartidos (acopla el mock a `ticketing`) |
| Consecuencia | Dos builds en el repositorio; reglas de arquitectura (ArchUnit) además del compilador |
| Fuente | [ADR-034](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-034-clean-architecture-structure-independent-mock.md), [ADR-030](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-030-payment-mock-independent-project-with-cancellation.md) |

### 2.2 Una tabla DynamoDB con cada Ticket en su propia partición

| | |
|---|---|
| Problema | Más de 1.000 compradores concentrados sobre un Event de 50.000 Ticket: si todos los Ticket comparten partición, la ruta de compra se calienta |
| Decisión | Tabla única `ticketing`; cada Ticket con clave propia `TICKET#<eventId>#<ticketId>`; Event, Order, idempotencia, bloqueo y auditoría con sus claves; `ticketId` determinista `<sección>-<fila>-<asiento>` |
| Alternativa descartada | Ticket en la colección del Event (`PK = EVENT#<eventId>`): partición caliente; una tabla por entidad: más recursos sin resolver ningún acceso adicional |
| Consecuencia | La disponibilidad pasa a leerse de un índice (eventual y paginada); la compra escribe en varias particiones dentro de una transacción |
| Fuente | [ADR-022](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-022-dynamodb-data-model-ticket-partitioning.md) |

### 2.3 Reserva atómica entre particiones

| | |
|---|---|
| Problema | Reservar todos los Ticket o ninguno (`AC-016`) y que solo una compra concurrente gane cada Ticket (`AC-007`) |
| Decisión | Una `TransactWriteItems` de N + 4 items (como máximo 14): cada Ticket con la condición "existe, pertenece al Event y está `AVAILABLE`", la Order con su Reservation, la idempotencia, la auditoría y el bloqueo de Order activa. El Event se valida antes y queda fuera de la transacción porque es inmutable una vez `ENABLED` |
| Alternativa descartada | Bloqueo optimista por versión (lecturas extra y predicado más débil) y escrituras por Ticket con compensación (reservas parciales observables) |
| Consecuencia | Bajo contención real sobre el mismo Ticket hay conflictos que se reintentan como máximo 2 veces con jitter; si persisten, 503 sin persistir nada. Ningún rechazo deja rastro |
| Fuente | [ADR-023](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-023-atomic-multi-ticket-reservation-cross-partition.md), [ADR-003](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-003-tickets-per-order-limit.md) (1 a 10 Ticket) |

### 2.4 Una transacción por transición y la Order como guardián

| | |
|---|---|
| Problema | Order y Ticket deben cambiar juntos en cada transición y solo un desenlace puede ganar |
| Decisión | Cada transición (iniciar pago, confirmar, rechazar, fallar, expirar) es una transacción de como máximo 13 items con la guarda `status = CREATED` en la Order y estado de origen y propiedad en cada Ticket. Cuarentena técnica cuando la Order sigue `CREATED` pero un Ticket no cumple su condición. Marca de reverso en la misma transacción cuando el pago fue aprobado o su resultado es desconocido |
| Alternativa descartada | Saga con estados intermedios (subconjuntos observables) y estado del Ticket derivado de la Order (sin exclusión por Ticket) |
| Consecuencia | Una compra confirmada cuesta tres transacciones; una Order en cuarentena retiene sus Ticket hasta revisión manual |
| Fuente | [ADR-025](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-025-state-transition-consistency-quarantine-reversal.md) |

### 2.5 Índices dispersos con sharding y disponibilidad derivada

| | |
|---|---|
| Problema | Listar disponibles de un Event de 50.000 Ticket sin leerlos todos y sin un contador compartido que todas las compras escriban |
| Decisión | `GSI2` disperso solo con Ticket `AVAILABLE`, repartido en `min(32, ceil(capacidad / 2000))` shards guardados en el Event (25 para 50.000). Página por cursor (máximo 100), filtro por sección y recuento en paralelo por shards cacheado 1 s. `GSI1` lista Events por ciclo de vida, `GSI3` es el índice de trabajo (expiración, reversos, cuarentena) y `GSI4` el de Orders pendientes de encolar |
| Alternativa descartada | Contador en el item Event (todas las compras escribirían el mismo item) y contador por captura de cambios (puede divergir) |
| Consecuencia | La cantidad disponible puede tener hasta 1 s de antigüedad y es informativa: la compra revalida en la transacción |
| Fuente | [ADR-040](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-040-availability-read-model-sharded-paginated.md), [ADR-022](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-022-dynamodb-data-model-ticket-partitioning.md) |

### 2.6 Persistir y encolar: publicación directa, compensación y barrido

| | |
|---|---|
| Problema | La Order se persiste en DynamoDB y el mensaje va a SQS: dos sistemas sin transacción común |
| Decisión | Tras el commit se publica directamente (500 ms por intento, 3 intentos, 2 s en total). Si falla de forma definitiva, la misma solicitud cierra la Order como `FAILED` con `PROCESSING_UNAVAILABLE` y libera los Ticket (201). Para una caída entre commit y publicación, un barrido cada 10 s republica Orders `CREATED` sin `enqueuedAt` de más de 30 s |
| Alternativa descartada | Outbox con relay como mecanismo principal: la respuesta síncrona no podría informar el fallo y añade latencia |
| Consecuencia | Duplicados ocasionales de mensajes, tolerados por el consumidor; dos escrituras de índice más por Order |
| Fuente | [ADR-026](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-026-persistence-plus-enqueue-with-republish-sweep.md) |

### 2.7 SQS al menos una vez, idempotencia y DLQ

| | |
|---|---|
| Problema | SQS Standard entrega al menos una vez y puede duplicar; un fallo transitorio no debe cobrar dos veces ni dejar la Order abierta |
| Decisión | Colas de Orders y de aprovisionamiento, cada una con su DLQ y 5 recepciones. Orders: visibilidad 60 s, tope de procesamiento 30 s, lease del PaymentAttempt 45 s, backoff por visibilidad de 5, 15, 30 y 60 s; en la última recepción la Order se cierra `FAILED` (con reverso si el resultado del pago es desconocido). Identidad de cada mensaje por `orderId` / `eventId`, nunca por el identificador de SQS; `paymentAttemptId = <orderId>-1` como clave de idempotencia ante el proveedor |
| Alternativa descartada | Consumidor automático de la DLQ (recicla mensajes venenosos), sin DLQ y cola FIFO (contraria a `TC-005`) |
| Consecuencia | Un duplicado concurrente consume una recepción; con doble fallo en la última recepción la Order termina `EXPIRED` en lugar de `FAILED` |
| Fuente | [ADR-029](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-029-sqs-operational-policy-orders-and-provisioning.md), [ADR-027](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-027-idempotency-purchase-event-creation.md) |

### 2.8 Aprovisionamiento asíncrono de Events

| | |
|---|---|
| Problema | Crear un Event de 50.000 Ticket en la misma solicitud tardaría lo que su capacidad y necesitaría un cuerpo de varios MB |
| Decisión | Definición compacta (secciones, filas, asientos, rangos de cortesía). La API valida todo, crea el Event en `PROVISIONING` y responde 202. El `worker` escribe por lotes de 100 con lease de 60 s y heartbeat, verifica con lectura fuerte que existen exactamente `capacity` Ticket y lo habilita; si falla de forma definitiva, `FAILED` y purga de sus Ticket. Detección de estancados cada 60 s |
| Alternativa descartada | Creación síncrona en dos fases y sondeo periódico sin cola (sin backoff ni DLQ) |
| Consecuencia | La creación se observa en dos pasos (202 y sondeo de `API-006`); un Event `ENABLED` es inmutable |
| Fuente | [ADR-024](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-024-asynchronous-event-provisioning.md) |

### 2.9 Carrera entre el pago y la expiración, y reversos

| | |
|---|---|
| Problema | La aprobación del pago puede llegar justo cuando vence la Reservation, y el proveedor puede haber cobrado sin que la compra se confirme |
| Decisión | Gana la primera transición final confirmada. Confirmar exige `expiresAt` posterior al instante actual; expirar exige lo contrario. No se inicia un pago si faltan menos de 15 s. Si la Order se cierra sin confirmar con un pago aprobado o desconocido, se marca para reverso en la misma transacción y un proceso cada 10 s cancela el intento con backoff (10 s, 30 s, 1 min, 2 min, 5 min y luego cada 10 min, 10 intentos); agotado, queda para revisión manual |
| Alternativa descartada | Confirmar aprobaciones tardías mientras la expiración no haya actuado (rompe los 10 minutos) y no expirar con un pago en curso (Reservation sin límite) |
| Consecuencia | Existe el caso "cobrado y reversado"; una aprobación tardía nunca reabre una Order |
| Fuente | [ADR-008](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-008-payment-versus-expiration-race.md), [ADR-025](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-025-state-transition-consistency-quarantine-reversal.md), [ADR-030](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-030-payment-mock-independent-project-with-cancellation.md) |

### 2.10 Procesos periódicos aislados

| | |
|---|---|
| Problema | La expiración tiene un plazo estricto (15 s tras `expiresAt`) y no debe retrasarse por otros procesos lentos |
| Decisión | Cuatro disparadores independientes en cada instancia del `worker`, sin líder: expiración cada 5 s (concurrencia 16), barrido cada 10 s (8), reversos cada 10 s (4), limpieza cada 60 s (2). Sin solape dentro de un proceso, fase inicial aleatoria entre instancias y espera creciente acotada tras un fallo |
| Alternativa descartada | TTL de DynamoDB (demora no acotada y destruye la Order consultable), mensaje diferido por Reservation y un único planificador secuencial |
| Consecuencia | Los Ticket de una Reservation vencida pueden quedar retenidos hasta 15 s; sin ninguna instancia del `worker`, nada expira |
| Fuente | [ADR-028](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-028-expiration-process-isolated-scheduling.md) |

### 2.11 Modelo de error, retry reactivo y circuit breakers

| | |
|---|---|
| Problema | Responder errores estables sin filtrar detalles y no saturar una dependencia caída con reintentos |
| Decisión | Problem Details con `code` estable y `traceId`, traducidos en un único punto. Retry solo en los adaptadores, nunca ante validaciones, condiciones, rechazos de pago ni con el circuito abierto. Circuit breaker del Payment Mock (20 llamadas, 50 % de fallos o de llamadas de más de 2 s, 15 s abierto, 3 de prueba): pausa el consumo de Orders y los reversos; la expiración sigue. Circuit breaker de publicación en SQS (20 llamadas, 50 %, 10 s, 2 de prueba): la compra responde 503 con `Retry-After` antes de reservar. DynamoDB sin circuito: es la fuente de verdad |
| Alternativa descartada | Cuerpo de error propio con retry genérico en los casos de uso, anotaciones HTTP en el dominio y circuito sobre DynamoDB |
| Consecuencia | El estado del circuito es por instancia; con SQS degradado puede haber rechazos 503 en masa, reintentables con la misma clave |
| Fuente | [ADR-035](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-035-error-model-reactive-retry-circuit-breaker.md), [ADR-039](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-039-aws-integration-technology-v2.md) |

### 2.12 Seguridad, propiedad y abuso

| | |
|---|---|
| Problema | Autenticar con Cognito, autorizar por rol y propiedad, y limitar reintentos maliciosos y acaparamiento de inventario |
| Decisión | El backend es Resource Server: valida firma RS256, emisor, expiración (60 s de tolerancia), `token_use = access` y `client_id`. Roles desde `cognito:groups`. El propietario de la Order es el `sub` del JWT; una Order ajena, inexistente o malformada responde igual (404). Una sola Order `CREATED` por cliente y Event mediante un item de bloqueo dentro de la transacción. Idempotencia obligatoria, cuerpo de como máximo 256 KB, 1 a 10 Ticket, límite de 10 compras por 10 s y sujeto. En local, `local-idp` emite tokens con la misma forma |
| Alternativa descartada | Validación solo en el borde (no hay borde en local y el backend dejaría de ser Resource Server) y límite de Order activa comprobado con una lectura previa (no atómico) |
| Consecuencia | El limitador de la aplicación es por instancia y en memoria; el control autoritativo de tasa y bots es el WAF en AWS (diseño). El acaparamiento con varias cuentas solo se mitiga en el borde |
| Fuente | [ADR-032](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-032-security-active-order-lock.md), [ADR-033](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-033-local-identity-provider-load-identities.md) |

### 2.13 Auditoría

| | |
|---|---|
| Problema | Las transiciones deben ser auditables (`FR-014`) sin que exista una transición sin evidencia |
| Decisión | Registro de solo inserción escrito en la misma transacción que la transición, con causa, actor, correlación e instante; catálogo que incluye reversos, aprobación tardía, cuarentena y aprovisionamiento |
| Alternativa descartada | Captura de cambios como mecanismo principal (evidencia asíncrona) y logs como evidencia (no consistentes) |
| Consecuencia | La auditoría crece en la tabla operativa y su inmutabilidad es por convención (PITR en AWS); mejora productiva: captura hacia almacenamiento con bloqueo de objetos |
| Fuente | [ADR-031](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-031-audit-trail-extended-catalog.md) |

### 2.14 Topología local

| | |
|---|---|
| Problema | Un entorno local completo con la misma forma que el objetivo, sin que la aplicación cree infraestructura |
| Decisión | Docker Compose con siete servicios: DynamoDB Local, LocalStack 4.14.0 fijado por digest y sin token, `local-idp`, `payment-mock`, `infra-init` (crea tabla, índices y colas una vez), `ticketing-api` y `ticketing-worker` (misma imagen). Perfil `load` con generador de tokens y servicio de carga |
| Alternativa descartada | Un contenedor con todos los roles que crea sus recursos al arrancar |
| Consecuencia | Más contenedores (unos 1 GiB de memoria en reposo); datos efímeros |
| Fuente | [ADR-036](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-036-local-topology-v2.md) |

### 2.15 Estrategia de pruebas

| | |
|---|---|
| Problema | Demostrar cobertura, ausencia de sobreventa y resiliencia de forma reproducible |
| Decisión | Pirámide con puerta del 90 % de líneas medida solo con pruebas sin contenedores; concurrencia probada por invariantes con solicitudes simultáneas, reloj inyectado y tiempo virtual; integración contra DynamoDB Local y LocalStack; extremo a extremo con la colección; carga externa con invariantes al terminar; tres escenarios de resiliencia sobre Compose |
| Alternativa descartada | Cobertura basada en integración y concurrencia probada solo con la carga (no determinista) |
| Consecuencia | La carga y los escenarios de resiliencia dependen del agente de QA (pendientes) |
| Fuente | [ADR-038](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-038-test-strategy-v2.md) |

### 2.16 Topología AWS (diseño)

Estado: `Designed, not implemented`. Detalle en §6.

| | |
|---|---|
| Problema | Operar en producción con escalado, aislamiento, seguridad de red y observabilidad |
| Decisión | ECS sobre Fargate con servicios `api` y `worker` de la misma imagen; ALB con TLS y WAF (reglas gestionadas, tasa y control de bots); escalado del `worker` por mensajes pendientes y antigüedad del más antiguo; apagado ordenado; catálogo de alarmas |
| Alternativa descartada | Funciones sin servidor (otro modelo de ejecución para procesos de larga vida) y Kubernetes gestionado (complejidad desproporcionada) |
| Consecuencia | Coste base por tareas mínimas y por el control de bots; con el Payment Mock caído el `worker` escala sin efecto útil hasta su máximo |
| Fuente | [ADR-037](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/adr/ADR-037-aws-target-topology-v2.md), [`ticketing.aws-target.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.aws-target.v2.md) |

## 3. Concurrencia, atomicidad e idempotencia

| Transición | Escrituras conjuntas | Guardas | Items máx. |
|---|---|---|---|
| Reservar y crear Order | N Ticket → `RESERVED`; Order `CREATED`; idempotencia; auditoría; bloqueo | Ticket existe, del Event, `AVAILABLE`; Order, idempotencia y bloqueo no existen | 14 |
| Iniciar pago | PaymentAttempt y lease; N Ticket → `PENDING_CONFIRMATION`; auditoría | Order `CREATED`, sin PaymentAttempt, `expiresAt > ahora + 15 s` | 12 |
| Confirmar | Order → `CONFIRMED`; N Ticket → `SOLD`; auditoría; bloqueo eliminado | Order `CREATED`, mismo PaymentAttempt, `expiresAt > ahora` | 13 |
| Rechazar, fallar o expirar | Order final; N Ticket → `AVAILABLE`; auditoría; bloqueo eliminado; marca de reverso si aplica | Order `CREATED` (expirar: `expiresAt <= ahora`) | 13 |

| Frente de idempotencia | Identidad | Comportamiento |
|---|---|---|
| Compra (`API-004`) | `sub` + `Idempotency-Key` (16 a 64 caracteres) | Repetición: 200 con `Idempotency-Replayed: true` y la Order actual; otro contenido: 422 `IDEMPOTENCY_KEY_REUSED`; un rechazo no persiste nada y su repetición se evalúa de nuevo |
| Creación de Event (`API-001`) | `sub` del `ADMIN` + `Idempotency-Key` | Igual; vigencia mínima de 24 h |
| Mensaje de Order (`MSG-001`) | `orderId` | Order final: se descarta; lease vigente de otro consumidor: se pospone |
| Mensaje de aprovisionamiento (`MSG-002`) | `eventId` | Event `ENABLED` o `FAILED`: se descarta; se reanuda por lotes confirmados |
| Pago ante el proveedor | `paymentAttemptId = <orderId>-1` | Autorización y cancelación idempotentes; cancelación anticipada hace rechazar el cobro posterior |

Evidencia (pruebas del backend, `Implemented and verified`): `DynamoDbMechanismIT` contra DynamoDB Local (INC-005): 8 compras simultáneas del mismo Ticket con un solo ganador y conjuntos solapados sin Ticket en dos Orders (AC-007); 5 compras simultáneas del mismo cliente con una Order y 4 `ACTIVE_ORDER_EXISTS` (AC-044); carrera de misma clave (`sameKeySameContent`, `sameKeyDifferentContent`). `RoleComponentIT` (INC-010): entregas duplicadas con una sola autorización (AC-023), fallo con reverso exactamente una vez, expiración con reloj inyectado (AC-008). Circuit breakers con tiempo virtual (`ManagedCircuitBreakerTest`, INC-006/007). La colección lo demuestra sobre Compose en las carpetas 04, 06, 07 y 09.

## 4. Seguridad

| Aspecto | Implementado y verificado | Diseño para AWS (`Designed, not implemented`) |
|---|---|---|
| Autenticación | Resource Server con validación de firma, emisor, expiración, `token_use` y `client_id` (INC-008) | User pool de Cognito por entorno |
| Autorización | Rol por operación y propiedad en el caso de uso; 401 sin token, 403 sin rol, 404 idéntico para Orders ajenas (colección 08) | Igual |
| Abuso | Idempotencia, 1 a 10 Ticket, 50.000 por Event, cuerpo de 256 KB (413), 10 compras por 10 s y sujeto (429), una Order activa por cliente y Event | WAF con reglas gestionadas, tasa y control de bots |
| Secretos | API key del mock solo en `.env` no versionado; credenciales AWS ficticias; imágenes, configuración resuelta y logs sin clave, JWT ni `PRIVATE KEY` (PLAT-INC-007 §5); nunca se registran tokens ni cabeceras de autorización | Roles de tarea sin claves estáticas; API key en el gestor de secretos |
| Contenedores | Usuario sin privilegios (10001), raíz de solo lectura, `cap_drop: ALL`, `no-new-privileges`, puertos solo en `127.0.0.1`, imágenes externas fijadas por tag y digest | Subredes privadas, endpoints privados, grupos de seguridad mínimos |
| Gestión | Métricas y salud de gestión en el puerto 8081, no expuesto en el puerto público | Puerto de gestión no registrado en el balanceador |

## 5. Observabilidad

| Señal | Estado | Detalle |
|---|---|---|
| Logs estructurados JSON (ECS) sin tokens | `Implemented and verified` | Campos `event`, `orderId`, `eventId`, `correlationId`; escritos fuera de los hilos reactivos (INC-010) |
| Métricas de negocio y técnicas en `/actuator/prometheus` | `Implemented and verified` | Reservas, Orders finales por causa, demora de expiración, reversos, aprovisionamiento, estado de cada circuito, DynamoDB, SQS, limitador (INC-010 §6.3) |
| Trazas propagadas de HTTP a los mensajes | `Implemented and verified` | `traceparent` hasta `MSG-001`/`MSG-002` y el `traceId` de los Problem Details; exportación OTLP desactivada por defecto |
| Profundidad y antigüedad de colas y DLQ | `Designed, not implemented` en la aplicación | Son métricas del servicio SQS (CloudWatch); la aplicación no las produce (DOC-ISSUE-004) |
| "Reversos pendientes" | `Designed, not implemented` como métrica directa | Se deriva de contadores: solicitados − confirmados − agotados (DOC-ISSUE-004) |
| "Orders expiradas sin encolar" | `Designed, not implemented` | Se produce "expiradas sin PaymentAttempt" (DOC-ISSUE-004) |
| Catálogo de alarmas, panel y retención de logs | `Designed, not implemented` | ADR-037: DLQ, antigüedad > 2 min, demora de expiración > 15 s, apertura de circuitos, reversos, cuarentena, aprovisionamientos fallidos, 5xx, p95, throttling |

## 6. Topología AWS (diseño)

Estado: `Designed, not implemented`. No hay Terraform ni otro código de infraestructura en este repositorio (EVAL-011); el diseño incluye el handoff para el agente de infraestructura ([aws-target v2 §11](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.aws-target.v2.md#11-handoff-to-platformiac)). Diagrama en [`diagrams.md`](diagrams.md#14-topología-objetivo-en-aws-designed-not-implemented).

| Tema | Diseño (aws-target v2) |
|---|---|
| Cómputo | ECS sobre Fargate: servicios `api` y `worker` de la misma imagen, dos o más zonas, dos tareas mínimas por servicio en producción; Payment Mock solo fuera de producción |
| Red | VPC por entorno, subredes privadas para las tareas, endpoints privados (DynamoDB, SQS, ECR, logs, métricas, secretos), salida controlada solo para las claves de Cognito y el proveedor real |
| Entrada | ALB público solo HTTPS con WAF; solo el puerto de aplicación y la salud registrados |
| IAM | Un rol de tarea por servicio con mínimo privilegio sobre la tabla, índices y colas concretas; rol de ejecución separado; sin claves estáticas; las DLQ no son escribibles por la aplicación |
| Secretos y configuración | API key en el gestor de secretos; configuración no sensible en la definición de tarea o almacén de parámetros; cifrado en reposo y TLS |
| Escalado | `api` por CPU y solicitudes; `worker` por mensajes pendientes por tarea y antigüedad del mensaje más antiguo, con máximo; DynamoDB on-demand y precalentamiento antes de ventas masivas como mejora |
| Coste | DynamoDB on-demand; escrituras transaccionales al doble de capacidad (tres por compra); hasta 32 consultas por recuento mitigadas por el caché; WAF con control de bots; endpoints con coste por hora; retención de logs acotada; etiquetado |
| Aislamiento y gobernanza | Una cuenta por entorno; nombres parametrizados; roles de despliegue por entorno; producción sin escritura interactiva; misma imagen promovida; presupuestos y alertas de coste |

Diferencias con el entorno local: emuladores en lugar de servicios gestionados, sin particionado ni throttling reales (el beneficio del particionado por Ticket solo se observa en AWS), una instancia por rol, sin cifrado ni PITR y limitador en memoria en lugar del WAF.

## 7. Limitaciones conocidas

`Known limitation` (arquitectura v2 §17 y observaciones de esta documentación):

| # | Limitación | Fuente |
|---|---|---|
| 1 | Los objetivos de carga (200 solicitudes/s, p95) son objetivos de prueba, no capacidad productiva | NFR-001, NFR-002, ADR-038 |
| 2 | Disponibilidad eventual y paginada: recuento con hasta 1 s de antigüedad y orden no global por sección | ADR-040 |
| 3 | Una Order en cuarentena retiene Ticket y bloqueo hasta revisión manual | RISK-018 |
| 4 | Un reverso agotado deja un cobro sin compra pendiente de revisión | RISK-020 |
| 5 | Riesgo residual en el aprovisionamiento si un `worker` pausado supera su lease | RISK-016 |
| 6 | Estado de los circuit breakers por instancia | RISK-017 |
| 7 | Acaparamiento con varias cuentas solo mitigado en el borde | ADR-032, ADR-037 |
| 8 | Doble fallo en la última recepción: la Order termina `EXPIRED` en lugar de `FAILED` | RISK-014 |
| 9 | Liberación de Ticket hasta 15 s después de `expiresAt` | AV-003 |
| 10 | Auditoría en la tabla operativa; inmutabilidad por convención | RISK-015 |
| 11 | Limitador de tasa de la aplicación en memoria y por instancia | ADR-032 |
| 12 | Sin importe en el pago: la especificación no define precios | Arquitectura v2 §17 |
| 13 | Identidad local simulada (los claims de un user pool real de Cognito no se han verificado) y LocalStack fijado a 4.14.0 | RISK-013, RISK-021 |
| 14 | La idempotencia de creación de Events dura 24 h; después, repetir crearía un Event nuevo | RISK-022 |
| 15 | El contrato del mock limita `customerRef` a 128 caracteres y `ticketing` envía el `sub` sin acotarlo; el emisor local acepta sujetos de hasta 128, así que en local no se alcanza, pero el comportamiento con un sujeto mayor no está definido | DOC-ISSUE-003 |
| 16 | El esquema `OutcomeRule` del contrato del mock no es satisfacible por un validador estricto; el mock aplica una tolerancia aprobada | DOC-ISSUE-001 |
| 17 | La carga (AC-029 a AC-031), el extremo a extremo certificado y los escenarios de resiliencia no tienen evidencia de QA | DOC-ISSUE-006 |

## 8. Cambios para un entorno productivo

| Tema | Cambio (arquitectura v2 §17) |
|---|---|
| Publicación | Outbox con relay por captura de cambios si la ventana residual entre commit y publicación no es aceptable |
| Capacidad | Warm throughput antes de aperturas de venta; pruebas de carga en AWS |
| Pagos | Proveedor real con idempotencia y cancelación equivalentes; conciliación periódica |
| Auditoría | Captura de cambios a almacenamiento con bloqueo de objetos y retención |
| Abuso | Limitación distribuida en el borde; control de bots |
| Resiliencia | Estado de circuito compartido si se requiere; estrategia multi-región |
| Operación | Despliegues progresivos, objetivos de nivel de servicio, procedimientos para DLQ, cuarentena y reversos agotados |
| Disponibilidad en tiempo real | Canal continuo (fuera del alcance actual) |
| Infraestructura | Materializar la topología de §6 como código (handoff de aws-target v2 §11) |

## 9. Riesgos principales

| ID | Riesgo | Mitigación | ADR |
|---|---|---|---|
| RISK-001 | Partición caliente por Event popular | Ticket con partición propia; shards en los índices; on-demand | ADR-022 |
| RISK-002 | Conflictos transaccionales sobre los mismos Ticket | Transacciones pequeñas, reintento acotado con jitter, 503 reintentable | ADR-023 |
| RISK-004 | Pago aprobado o desconocido sin compra | Margen de corte, guarda temporal, marca de reverso, cancelación anticipada | ADR-008, ADR-025, ADR-030 |
| RISK-005 | Demora de expiración superior a 15 s | Ciclo aislado cada 5 s, varias instancias, alarma | ADR-028 |
| RISK-007 | Emuladores con comportamiento distinto de AWS | Verificación temprana; pruebas en AWS | ADR-036, ADR-038 |
| RISK-008 | El entorno local no alcanza la carga objetivo | Repetir la carga en AWS e informarla con su entorno | ADR-038 |
| RISK-010 | Acaparamiento de inventario | Máximo por Order, una Order activa, tasa, bots, expiración | ADR-003, ADR-032, ADR-037 |
| RISK-013 | Token local distinto del de Cognito | Prueba de contrato de claims; perfil con user pool real | ADR-033 |

Lista completa en [arquitectura v2 §12](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md#12-risks).
