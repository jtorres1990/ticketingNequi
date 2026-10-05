# Diagramas

Diagramas Mermaid de la arquitectura vigente. Cada bloque es una **copia literal** del diagrama aprobado en `SPEC_REPO` (mismo texto, nodos y flechas), con su origen indicado; no se añade ningún elemento. Las etiquetas están en español, como en la arquitectura aprobada. GitHub los muestra como imagen.

Los ID de acceso a datos (`AP-*`) y de mensajes (`MSG-*`) que aparecen en las secuencias se definen en [`ticketing.data-model.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.data-model.v2.md) y [`ticketing.messaging.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.messaging.v2.md).

| Diagrama | Origen |
|---|---|
| [1. Contexto del sistema](#1-contexto-del-sistema) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §3 |
| [2. Contenedores y dependencias](#2-contenedores-y-dependencias) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §4 |
| [3. Creación y aprovisionamiento asíncrono de un Event (MF-001)](#3-creación-y-aprovisionamiento-asíncrono-de-un-event-mf-001) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.8 |
| [4. Compra: flujo feliz (MF-003)](#4-compra-flujo-feliz-mf-003) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.1 |
| [5. Compra: Ticket no disponible y Order activa existente](#5-compra-ticket-no-disponible-y-order-activa-existente) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.2 |
| [6. Compra: pago rechazado](#6-compra-pago-rechazado) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.3 |
| [7. Fallo de encolado, circuito abierto y barrido de republicación](#7-fallo-de-encolado-circuito-abierto-y-barrido-de-republicación) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.4 |
| [8. Mensaje duplicado](#8-mensaje-duplicado) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.5 |
| [9. Expiración de la Reservation](#9-expiración-de-la-reservation) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.6 |
| [10. Carrera entre el pago y la expiración](#10-carrera-entre-el-pago-y-la-expiración) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.7 |
| [11. Reverso de pago con cancelación anticipada (MF-005)](#11-reverso-de-pago-con-cancelación-anticipada-mf-005) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.9 |
| [12. Carrera con la misma Idempotency-Key](#12-carrera-con-la-misma-idempotency-key) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.10 |
| [13. Caída del Payment Mock y circuit breaker](#13-caída-del-payment-mock-y-circuit-breaker) | [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.11 |
| [14. Topología objetivo en AWS (`Designed, not implemented`)](#14-topología-objetivo-en-aws-designed-not-implemented) | [`ticketing.aws-target.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.aws-target.v2.md) §1 |

## 1. Contexto del sistema

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §3. Decisiones: ADR-032, ADR-033.

Actores (`ADMIN`, `CUSTOMER`), el emisor de JWT y el Payment Mock como sistema externo. En local, el papel de Cognito lo cumple `local-idp` (ADR-033).

```mermaid
flowchart LR
    admin[ADMIN]
    customer[CUSTOMER]
    cognito[Amazon Cognito<br/>emisor de JWT]
    system[Ticketing Event Processing Platform]
    payment[Payment Mock<br/>proyecto independiente]

    admin -->|crea Events y consulta su aprovisionamiento,<br/>lista y consulta disponibilidad| system
    customer -->|lista Events, consulta disponibilidad,<br/>inicia compras, consulta sus Orders| system
    admin -.->|se autentica| cognito
    customer -.->|se autentica| cognito
    system -.->|valida JWT con claves publicas| cognito
    system -->|autoriza y cancela pagos| payment
```

## 2. Contenedores y dependencias

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §4. Decisiones: ADR-022, ADR-029, ADR-034, ADR-036.

Una imagen con dos roles (`ticketing-api` y `ticketing-worker`), una tabla DynamoDB con cuatro índices, dos colas SQS con sus DLQ y el Payment Mock como contenedor propio. Es la misma forma que el entorno Docker Compose (ADR-036).

```mermaid
flowchart TB
    client[Cliente HTTP]
    idp[Proveedor de identidad<br/>Cognito en AWS - emisor OIDC local]

    subgraph app[ticketing - una imagen, dos roles]
        api[ticketing-api<br/>rol api - WebFlux]
        worker[ticketing-worker<br/>consumidores Orders y aprovisionamiento<br/>expiracion, barrido, reversos, limpieza]
    end

    ddb[(DynamoDB tabla ticketing<br/>GSI1 a GSI4)]
    qo[[SQS ticketing-orders]]
    qod[[ticketing-orders-dlq]]
    qp[[SQS ticketing-event-provisioning]]
    qpd[[ticketing-event-provisioning-dlq]]
    mock[payment-mock<br/>proyecto y contenedor propios]

    client -->|HTTP + JWT| api
    api -.->|claves publicas| idp
    api -->|lecturas y transacciones| ddb
    api -->|MSG-001| qo
    api -->|MSG-002| qp
    worker -->|MSG-001 barrido| qo
    worker -->|MSG-002 estancados| qp
    qo -->|al menos una vez| worker
    qp -->|al menos una vez| worker
    qo -->|redrive| qod
    qp -->|redrive| qpd
    worker -->|lecturas, lotes y transacciones| ddb
    worker -->|autorizar y cancelar| mock
```

## 3. Creación y aprovisionamiento asíncrono de un Event (MF-001)

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.8. Decisiones: ADR-024, ADR-027, ADR-029.

La API responde 202 tras crear el Event en `PROVISIONING`; el `worker` escribe los Ticket por lotes con lease, verifica que existen exactamente `capacity` y habilita el Event. Colección: carpeta 02.

```mermaid
sequenceDiagram
    autonumber
    actor A as ADMIN
    participant API as ticketing-api
    participant DB as DynamoDB
    participant QP as SQS ticketing-event-provisioning
    participant W as ticketing-worker

    A->>API: POST /events con Idempotency-Key y definicion compacta
    API->>DB: AP-022 idempotencia de creacion - no existe
    API->>API: Validar, capacidad igual a asientos, maximo 50000
    API->>DB: AP-001 - Event PROVISIONING, idempotencia, auditoria
    API->>QP: Publicar MSG-002 con eventId
    API-->>A: 202 eventId PROVISIONING con Location
    QP->>W: Entregar MSG-002
    W->>DB: AP-024 tomar lease - PROVISIONING
    loop Por lote de 100 Tickets
        W->>DB: AP-024 comprobar PROVISIONING y lease propio, progreso
        W->>DB: AP-002 escritura por lotes
        W->>QP: Heartbeat de visibilidad
    end
    W->>DB: AP-025 verificar capacity Tickets con lectura consistente
    W->>DB: AP-003 - ENABLED y auditoria
    W->>QP: Eliminar
    A->>API: GET /events/eventId/provisioning
    API-->>A: 200 ENABLED
    Note over QP,W: Reentrega posterior - Event ENABLED, no obtiene lease, elimina sin escribir
```

## 4. Compra: flujo feliz (MF-003)

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.1. Decisiones: ADR-023, ADR-025, ADR-026, ADR-027, ADR-032.

Reserva atómica de los Ticket, publicación del mensaje con presupuesto acotado y respuesta 201 sin esperar al pago; el `worker` cobra y confirma. Colección: carpeta 04.

```mermaid
sequenceDiagram
    autonumber
    actor C as CUSTOMER
    participant API as ticketing-api
    participant DB as DynamoDB
    participant Q as SQS ticketing-orders
    participant W as ticketing-worker
    participant P as Payment Mock

    C->>API: POST /orders con JWT, Idempotency-Key, eventId, ticketIds
    API->>API: JWT, rol CUSTOMER, 1 a 10 sin repetidos
    API->>DB: Leer idempotencia AP-009 consistente
    DB-->>API: No existe
    API->>DB: Leer Event AP-006 o cache
    API->>API: ENABLED, futuro, ticketIds en la definicion, circuito SQS cerrado
    API->>DB: Transaccion AP-008 - Tickets RESERVED, Order CREATED, idempotencia, auditoria, bloqueo
    DB-->>API: Confirmada
    API->>Q: Publicar MSG-001, 500 ms por intento
    Q-->>API: Aceptado
    API->>DB: Marcar enqueuedAt AP-011, sale de GSI4
    API-->>C: 201 Order CREATED
    Q->>W: Entregar MSG-001
    W->>DB: Leer Order AP-010
    W->>DB: Transaccion AP-012 - PENDING_CONFIRMATION, PaymentAttempt, lease
    W->>P: Autorizar con paymentAttemptId
    P-->>W: APPROVED
    W->>DB: Transaccion AP-014 - CONFIRMED, SOLD, auditoria, bloqueo eliminado
    W->>Q: Eliminar mensaje
    C->>API: GET /orders/orderId
    API-->>C: 200 Order CONFIRMED
```

## 5. Compra: Ticket no disponible y Order activa existente

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.2. Decisiones: ADR-023, ADR-032, ADR-035.

Dos compras concurrentes sobre el mismo Ticket: una gana y la otra recibe 409 sin crear nada. Una segunda compra del mismo cliente en el mismo Event recibe `ACTIVE_ORDER_EXISTS`. Colección: 07.06 y 08.13.

```mermaid
sequenceDiagram
    autonumber
    actor A as CUSTOMER A
    actor B as CUSTOMER B
    participant API as ticketing-api
    participant DB as DynamoDB

    par Solicitudes concurrentes sobre T1
        A->>API: POST /orders con T1 y T2
    and
        B->>API: POST /orders con T1 y T3
    end
    API->>DB: AP-008 de A
    API->>DB: AP-008 de B
    DB-->>API: A confirmada
    DB-->>API: B cancelada - condicion de T1
    API->>DB: Releer idempotencia de B - no existe
    API-->>A: 201 Order CREATED
    API-->>B: 409 TICKETS_UNAVAILABLE sin Order ni Order ID
    A->>API: POST /orders mismo Event, otra clave, T5
    API->>DB: AP-008
    DB-->>API: Cancelada - condicion del bloqueo de A
    API->>DB: Releer idempotencia - no existe
    API-->>A: 409 ACTIVE_ORDER_EXISTS sin Order ni Order ID
```

## 6. Compra: pago rechazado

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.3. Decisiones: ADR-025, ADR-030.

El rechazo cierra la Order como `REJECTED` y libera sus Ticket en la misma transacción. Colección: carpeta 05.

```mermaid
sequenceDiagram
    autonumber
    participant Q as SQS ticketing-orders
    participant W as ticketing-worker
    participant DB as DynamoDB
    participant P as Payment Mock
    actor C as CUSTOMER
    participant API as ticketing-api

    Q->>W: Entregar MSG-001
    W->>DB: Leer Order - CREATED
    W->>DB: AP-012 - PENDING_CONFIRMATION
    W->>P: Autorizar con paymentAttemptId
    P-->>W: DECLINED
    W->>DB: AP-015 - REJECTED, Tickets AVAILABLE, auditoria, bloqueo eliminado
    W->>Q: Eliminar mensaje
    C->>API: GET /orders/orderId
    API-->>C: 200 Order REJECTED con PAYMENT_DECLINED
```

## 7. Fallo de encolado, circuito abierto y barrido de republicación

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.4. Decisiones: ADR-026, ADR-035.

Si la publicación falla de forma definitiva la Order queda `FAILED`; con el circuito de publicación abierto la compra recibe 503 sin reservar; el barrido republica Orders que quedaron sin mensaje. Procedimiento de resiliencia 3.

```mermaid
sequenceDiagram
    autonumber
    actor C as CUSTOMER
    participant API as ticketing-api
    participant DB as DynamoDB
    participant Q as SQS ticketing-orders
    participant S as Barrido worker

    C->>API: POST /orders
    API->>DB: AP-008
    DB-->>API: Confirmada - CREATED, en GSI4
    loop Hasta 3 intentos, 500 ms cada uno, 2 s en total
        API->>Q: Publicar MSG-001
        Q--xAPI: Error transitorio
    end
    Note over API: Fallo definitivo de encolado
    API->>DB: AP-015 - FAILED, Tickets AVAILABLE, auditoria, bloqueo eliminado
    API-->>C: 201 Order FAILED con PROCESSING_UNAVAILABLE
    Note over API: Circuito de publicacion abierto tras fallos repetidos
    C->>API: POST /orders nueva compra
    API-->>C: 503 SERVICE_UNAVAILABLE con Retry-After, sin reservar
    Note over S: Caso de caida entre commit y publicacion
    S->>DB: AP-028 - GSI4, creadas hace mas de 30 s
    S->>DB: Leer Order - CREATED sin enqueuedAt, restan 15 s o mas
    S->>Q: Publicar MSG-001
    S->>DB: AP-011 marcar enqueuedAt
```

## 8. Mensaje duplicado

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.5. Decisiones: ADR-027, ADR-029.

Entrega al menos una vez: el lease del PaymentAttempt y el estado de la Order hacen que una entrega duplicada no repita el cobro. La colección no envía mensajes duplicados; 09.1 muestra una recepción pospuesta hasta el fin del lease y la reanudación del mismo PaymentAttempt.

```mermaid
sequenceDiagram
    autonumber
    participant Q as SQS ticketing-orders
    participant W1 as worker 1
    participant W2 as worker 2
    participant DB as DynamoDB
    participant P as Payment Mock

    Q->>W1: Entregar MSG-001
    Q->>W2: Entregar el mismo MSG-001
    W1->>DB: AP-012 - abre PaymentAttempt y lease
    DB-->>W1: Confirmada
    W2->>DB: Leer Order - lease vigente de worker 1
    W2->>Q: Posponer visibilidad hasta el fin del lease
    W1->>P: Autorizar con paymentAttemptId
    P-->>W1: APPROVED
    W1->>DB: AP-014 - CONFIRMED
    W1->>Q: Eliminar
    Q->>W2: Reentrega
    W2->>DB: Leer Order - CONFIRMED
    W2->>Q: Eliminar sin efectos
```

## 9. Expiración de la Reservation

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.6. Decisiones: ADR-028, ADR-025.

Proceso periódico cada 5 s, con planificación propia, que libera Reservation vencidas como máximo 15 s después de `expiresAt`.

```mermaid
sequenceDiagram
    autonumber
    participant S as Proceso de expiracion
    participant DB as DynamoDB

    loop Cada 5 s, planificacion propia, en cada instancia
        S->>DB: AP-016 - GSI3 rango RESV por shard, vencidas
        DB-->>S: Candidatas
        loop Por candidata, concurrencia propia
            S->>DB: Leer Order AP-010
            alt CREATED, sin cuarentena, vencida
                S->>DB: AP-015 - EXPIRED, Tickets AVAILABLE, bloqueo eliminado, marca de reverso si hay PaymentAttempt
                DB-->>S: Confirmada o cancelada por condicion de Order
            else Cancelada por condicion de un Ticket con Order CREATED
                S->>DB: AP-031 - cuarentena y auditoria
            else Terminal
                Note over S: Sin accion
            end
        end
    end
```

## 10. Carrera entre el pago y la expiración

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.7. Decisiones: ADR-008, ADR-025, ADR-030.

La Order es el guardián: confirmar y expirar compiten y exactamente una gana; una aprobación tardía no confirma y se reversa.

```mermaid
sequenceDiagram
    autonumber
    participant W as ticketing-worker
    participant P as Payment Mock
    participant DB as DynamoDB
    participant S as Proceso de expiracion
    participant R as Proceso de reversos

    W->>P: Autorizar con paymentAttemptId
    Note over DB: Se alcanza expiresAt
    par
        P-->>W: APPROVED
        W->>DB: Confirmar - CREATED y expiresAt posterior a ahora
    and
        S->>DB: Expirar - CREATED y expiresAt menor o igual a ahora, marca de reverso
    end
    alt Confirmar primero y a tiempo
        DB-->>W: CONFIRMED
        DB-->>S: Cancelada por condicion
    else Expirar primero o Confirmar tarde
        DB-->>S: EXPIRED, reverso pendiente
        DB-->>W: Cancelada por condicion
        W->>DB: AP-032 - auditoria LATE_APPROVAL_NOT_APPLIED
        R->>P: Cancelar paymentAttemptId
        P-->>R: REVERSED
        R->>DB: AP-030 - reverso confirmado y auditoria
    end
```

## 11. Reverso de pago con cancelación anticipada (MF-005)

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.9. Decisiones: ADR-025, ADR-030, ADR-031.

Una Order cerrada con resultado de pago desconocido queda marcada para reverso y el proceso de reversos cancela el intento. Colección: 09.2.

```mermaid
sequenceDiagram
    autonumber
    participant W as ticketing-worker
    participant P as Payment Mock
    participant DB as DynamoDB
    participant R as Proceso de reversos

    W->>P: Autorizar - timeout, resultado desconocido
    Note over W: Quinta recepcion agotada
    W->>DB: AP-015 - FAILED, Tickets AVAILABLE, marca de reverso, GSI3 rango REVERSAL
    R->>DB: AP-029 - reversos con proximo intento vencido
    R->>P: Cancelar paymentAttemptId
    P-->>R: REGISTERED_BEFORE_CHARGE
    R->>DB: AP-030 - reverso confirmado y auditoria
    Note over P: Cobro en transito que llega despues
    P-->>W: DECLINED con ATTEMPT_CANCELLED
```

## 12. Carrera con la misma Idempotency-Key

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.10. Decisiones: ADR-027.

Dos solicitudes simultáneas con la misma clave producen una sola Order; la perdedora recibe la misma Order como repetición.

```mermaid
sequenceDiagram
    autonumber
    actor C as CUSTOMER
    participant API1 as ticketing-api instancia 1
    participant API2 as ticketing-api instancia 2
    participant DB as DynamoDB

    par Misma clave y contenido
        C->>API1: POST /orders clave K
    and
        C->>API2: POST /orders clave K
    end
    API1->>DB: AP-009 - no existe
    API2->>DB: AP-009 - no existe
    API1->>DB: AP-008
    API2->>DB: AP-008
    DB-->>API1: Confirmada
    DB-->>API2: Cancelada - idempotencia, bloqueo y Tickets
    API2->>DB: Releer idempotencia consistente - existe, mismo hash
    API1-->>C: 201 Order CREATED
    API2-->>C: 200 misma Order con Idempotency-Replayed
```

## 13. Caída del Payment Mock y circuit breaker

Origen: [`ticketing.architecture.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.architecture.v2.md) §7.11. Decisiones: ADR-035, ADR-039, ADR-028.

Con el circuito abierto el consumo de Orders se pausa sin gastar recepciones y la expiración sigue. Procedimiento de resiliencia 2.

```mermaid
sequenceDiagram
    autonumber
    participant W as Consumidor de Orders
    participant CB as Circuit breaker del Payment Mock
    participant P as Payment Mock
    participant Q as SQS ticketing-orders
    participant S as Proceso de expiracion

    W->>CB: Autorizar
    CB->>P: Llamada
    P--xCB: Timeout o 5xx repetidos
    Note over CB: 50 por ciento de fallos en 20 llamadas - abierto 15 s
    CB-->>W: Dependencia no disponible
    W->>Q: No eliminar, backoff por visibilidad
    Note over W: Bucle pausado, no consume recepciones
    S->>S: Expiracion continua en su ciclo
    Note over CB: Semiabierto - 3 llamadas de prueba
    W->>Q: Recibir mensajes de prueba
    W->>CB: Autorizar
    CB->>P: Llamada
    P-->>CB: APPROVED
    Note over CB: Cerrado - consumo reanudado
```

## 14. Topología objetivo en AWS (`Designed, not implemented`)

Origen: [`ticketing.aws-target.v2.md`](https://github.com/jtorres1990/PruebaeTecnicaNequi/blob/main/architecture/ticketing.aws-target.v2.md) §1. Decisiones: ADR-037.

Diseño aprobado, sin infraestructura como código ni despliegue: ECS Fargate con los servicios `api` y `worker`, ALB con WAF, VPC multi-AZ con endpoints privados, DynamoDB, SQS, Cognito, gestor de secretos y CloudWatch.

```mermaid
flowchart TB
    client[Clientes HTTP]
    cognito[Amazon Cognito User Pool]

    subgraph edge[Borde]
        waf[AWS WAF - reglas gestionadas, tasa y control de bots]
        alb[Application Load Balancer - TLS]
    end

    subgraph vpc[VPC por entorno - multi AZ]
        subgraph public[Subredes publicas]
            albnode[Nodos del balanceador]
            egress[Salida controlada a Internet]
        end
        subgraph private[Subredes privadas]
            api[Servicio api - ECS Fargate]
            worker[Servicio worker - ECS Fargate<br/>consumidores y procesos periodicos]
            mock[Payment Mock - solo no productivo]
            endpoints[Endpoints privados de VPC]
        end
    end

    ddb[(Amazon DynamoDB - tabla ticketing<br/>GSI1 a GSI4)]
    qo[[SQS ticketing-orders]]
    qod[[SQS ticketing-orders-dlq]]
    qp[[SQS ticketing-event-provisioning]]
    qpd[[SQS ticketing-event-provisioning-dlq]]
    secrets[Gestor de secretos]
    obs[CloudWatch - logs, metricas, alarmas, trazas]
    ecr[Registro de imagenes]

    client -->|HTTPS + JWT| waf --> alb --> albnode -->|solo puerto de aplicacion y salud| api
    client -.->|autenticacion| cognito
    api -.->|claves publicas JWT| egress -.-> cognito
    api --> endpoints
    worker --> endpoints
    endpoints --> ddb
    endpoints --> qo
    endpoints --> qp
    endpoints --> secrets
    endpoints --> obs
    endpoints --> ecr
    qo -->|redrive| qod
    qp -->|redrive| qpd
    worker -->|autorizar y cancelar pagos| mock
```
