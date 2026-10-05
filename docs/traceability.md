# Trazabilidad documental

Matriz que relaciona los entregables, flujos, operaciones y criterios del proyecto con el lugar donde se documentan o demuestran, y con su estado de verificación. Las etiquetas de estado se explican en el [README §2](../README.md#2-estado-de-implementación-y-verificación).

Estado de esta matriz: final de la documentación (DOC-INC-006, 2026-10-05). Estado global: **`DOCUMENTATION_COMPLETE_WITH_PENDING_QA`**: la documentación está completa y los resultados de carga, extremo a extremo y resiliencia siguen pendientes de QA.

## 1. Entregables

| ID | Cubierto por | Estado |
|---|---|---|
| DEL-002 | [`README.md`](../README.md) y [`README.en.md`](../README.en.md) §1 a §23: descripción (§1 a §3), instalación (§5, §7), configuración (§6), comandos Docker (§7, §8), decisiones (§14 y [`architecture.md`](architecture.md)), ejemplos de endpoints (§11); más `docs/` | `Implemented and verified`: comandos ejecutados (DOC-INC-002 a DOC-INC-006) |
| DEL-004 | [`postman/ticketing-demo.postman_collection.json`](../postman/ticketing-demo.postman_collection.json), curl de README §11, [`collection-guide.md`](collection-guide.md) | `Implemented and verified` (DOC-INC-003, DOC-INC-006) |

## 2. Flujos principales

| ID | Colección | README | Otros | Estado |
|---|---|---|---|---|
| MF-001 Crear y aprovisionar Event | 02 | §11 (curl con sondeo) | [`diagrams.md`](diagrams.md#3-creación-y-aprovisionamiento-asíncrono-de-un-event-mf-001) | Demostrado; E2E de QA `NOT_YET_VALIDATED_BY_QA` |
| MF-002 Consultar Events y disponibilidad | 03 | §11 | [`diagrams.md`](diagrams.md#2-contenedores-y-dependencias) | Demostrado |
| MF-003 Iniciar y confirmar compra | 04, 05, 06, 07 | §11 | [`diagrams.md`](diagrams.md#4-compra-flujo-feliz-mf-003) | Demostrado |
| MF-004 Consultar Order | 04, 08 | §11 | — | Demostrado |
| MF-005 Reversar pago no aplicado | 09.2 (lenta) | §15, §18 | [`diagrams.md`](diagrams.md#11-reverso-de-pago-con-cancelación-anticipada-mf-005) | Demostrado en su variante de resultado desconocido (`REGISTERED_BEFORE_CHARGE`) |

## 3. Operaciones

| ID | Colección | README | Estado |
|---|---|---|---|
| API-001 | 02, 08, 09.3 | §11 | Demostrada; contrato G3 |
| API-002 | 03, 08, 09.3 | §11 | Demostrada; contrato G3 |
| API-003 | 03, 04, 05, 09 | §11 | Demostrada; contrato G3 |
| API-004 | 04 a 09 | §11 | Demostrada; contrato G3 |
| API-005 | 04 a 09 | §11 | Demostrada; contrato G3 |
| API-006 | 02 | §11 | Demostrada; contrato G3 |
| API-101, API-102 | No se invocan a mano: el `worker` las usa en cada compra y reverso | §10 | Indirecta |
| API-103 a API-107, API-110 | 01, 05, 06, 07, 09, 99 | §10 | Demostradas; contrato G3 |
| API-108, API-109 | 04, 05, 06, 09 | §10 | Demostradas; contrato G3 |
| API-111 | 00 | §8, §10 | Demostrada |

## 4. Criterios de aceptación demostrados por la colección

Demostración documental sobre Docker Compose, no certificación de QA.

| AC | Solicitudes |
|---|---|
| AC-001 | 02.01, 02.04 |
| AC-002 | 03.01, 09.3.6 |
| AC-003, AC-004 | 04.02 |
| AC-005, AC-006, AC-019 | 04.04 |
| AC-010 | 03.02 |
| AC-016 | 08.13 |
| AC-017 | 02.06 |
| AC-020 | 05.06 |
| AC-021 | 06.05 (error definitivo), 09.2.5 (agotamiento) |
| AC-023, AC-024 | 04.05, 06.12, 09.1.6 |
| AC-027 | 08.07 a 08.09 |
| AC-028 | 02.05, 08.02 a 08.05 |
| AC-032 | 08.11, 08.12 |
| AC-033 | 02.07 |
| AC-036 | 07.09 |
| AC-037, AC-038 | 09.3.4, 09.3.5 |
| AC-041, AC-042 | 02.02, 02.03 |
| AC-043 | 07.06 |
| AC-045 | 04.07, 07.09 |
| AC-048 | 03.05, 03.06 |

Los demás AC se verifican con las pruebas del backend (INC-001 a INC-010) o quedan para QA (carga AC-029 a AC-031; resiliencia). Ver [`collection-guide.md`](collection-guide.md#6-lo-que-la-colección-no-demuestra).

## 5. Criterios de evaluación

| EVAL | Ubicación | Estado |
|---|---|---|
| EVAL-001 | README §2, §11, §12; colección | Documentado y demostrado |
| EVAL-002 | README §14; [`architecture.md`](architecture.md#2-decisiones) | Documentado |
| EVAL-003 | README §15; [`architecture.md`](architecture.md#3-concurrencia-atomicidad-e-idempotencia); colección 07, 08 | Documentado; carga `NOT_YET_VALIDATED_BY_QA` |
| EVAL-004 | [`diagrams.md`](diagrams.md) §3 a §8; README §14; colección con sondeo | Documentado |
| EVAL-005 | README §18; [`resilience.md`](resilience.md); [`architecture.md`](architecture.md#2-decisiones) §2.7, §2.11 | Mecanismos `Implemented and verified`; escenarios de ADR-038 `NOT_YET_VALIDATED_BY_QA` (comandos ejecutados en DOC-INC-005) |
| EVAL-006 | README §16; [`architecture.md`](architecture.md#4-seguridad); colección 08 | Documentado y demostrado |
| EVAL-007 | README §6, §16; [`operations.md`](operations.md#2-variables-de-env) | Documentado |
| EVAL-008 | README §15, §16; colección 07, 08 | Documentado y demostrado |
| EVAL-009 | Ambos README con el mismo índice; [`demo-guide.md`](demo-guide.md) | Documentado |
| EVAL-010 | [`diagrams.md`](diagrams.md) (14 diagramas); README §3 | Documentado |
| EVAL-011 | README §2, §20; [`architecture.md`](architecture.md#6-topología-aws-diseño) | `Designed, not implemented`: sin código de infraestructura |
| EVAL-012 | README §20; [`architecture.md`](architecture.md#6-topología-aws-diseño) | `Designed, not implemented` |
| EVAL-013 | README §17; [`operations.md`](operations.md#6-salud-métricas-logs-y-trazas); [`architecture.md`](architecture.md#5-observabilidad) | Observabilidad local implementada; costes, aislamiento y gobernanza diseñados |
| EVAL-014 | README §19, §20; [`architecture.md`](architecture.md#7-limitaciones-conocidas) | Documentado |

## 6. Arquitectura v2 §16 y §17 y escenarios de ADR-038

| Elemento | Ubicación |
|---|---|
| §16, cobertura de EVAL | §5 de esta matriz |
| §17, limitaciones 1 a 14 | README §19; [`architecture.md`](architecture.md#7-limitaciones-conocidas). La limitación 15 ya no aplica: la especificación v5 incorporó las aclaraciones (DOC-ISSUE-009) |
| §17, cambios en producción | README §20; [`architecture.md`](architecture.md#8-cambios-para-un-entorno-productivo) |
| ADR-038, escenarios 1 a 3 | README §18; [`resilience.md`](resilience.md) (`NOT_YET_VALIDATED_BY_QA`) |

## 7. Evidencia de la verificación documental

| Comprobación | Resultado | Incremento |
|---|---|---|
| `docker compose up -d --wait` con imágenes construidas | 24,8 s y 27,7 s | DOC-INC-002, DOC-INC-006 |
| `environment.sh`, `resources.sh`, `identity.sh --skip-restart` | 65/0, 38/0, 38/0 | DOC-INC-002 |
| `./mvnw verify` en `ticketing/` y `payment-mock/` | 770 pruebas y 99,49 %; 256 pruebas | DOC-INC-002 |
| `run-local.sh start`, `smoke`, `stop` | `SMOKE OK` (`CONFIRMED`) | DOC-INC-002 |
| Colección, ejecución por defecto | 0 fallos en tres ejecuciones | DOC-INC-003, DOC-INC-006 |
| Colección, escenarios lentos | 0 fallos | DOC-INC-003 |
| Bloques `sh`, `bash` y `powershell` de README §8 a §11 y de `resilience.md`, ejecutados tal cual | exit 0 | DOC-INC-002, DOC-INC-003, DOC-INC-005, DOC-INC-006 |
| Gates G1 a G4, G6 y G7 | 0 fallos | DOC-INC-006 |

Los informes completos de cada incremento están en `SPEC_REPO` (`implementation/documentation-increments/`).
