# TiempoJusto P2.2 Admin / HumanReviewQueue V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Materializar el mínimo Admin requerido para Safety, evidencia, apelaciones y auditoría financiera de solo lectura, sin introducir reglas nuevas de producto.

## Alcance

- HumanReviewQueue para casos S5 y acciones irreversibles.
- Claim explícito de tareas por un ADMIN activo.
- SafetyCase detail con subject, report, severity, decision status y appeal deadline.
- Evidence Timeline construida desde `safety.evidence_item` y `platform.audit_event`.
- Decisión humana con outcomes `CONFIRMED`, `UNDETERMINED` o `DISMISSED`.
- Appeal queue y resolución `MAINTAIN`, `REDUCE` o `REVOKE`.
- Risk signals privados y nunca expuestos como reputación pública.
- Payout y ledger audit read-only.
- AdminAction + AuditEvent para acciones humanas.

## Reglas V1.7 reflejadas

- S5 requiere revisión humana.
- Acciones irreversibles entran a HumanReviewQueue.
- Automatización Safety debe seguir siendo reversible donde corresponda.
- `UNDETERMINED` no implica culpabilidad pública ni infracción reputacional.
- Appeal normal tiene ventana de 7 días cuando existe una decisión apelable.
- La timeline conserva la decisión y los eventos administrativos mediante auditoría append-only.
- `RiskSignal` es privado.
- `SafetyEvidence` debe ser mínima y relevante; las llamadas privadas no se graban por defecto.
- Las herramientas financieras de Admin son de solo lectura. No existe endpoint Admin para editar ledger, payout o settlement.

## Endpoints V1

- `GET /api/v1/admin/human-review-queue`
- `POST /api/v1/admin/human-review-queue/{taskId}/claim`
- `GET /api/v1/admin/safety-cases/{caseId}`
- `GET /api/v1/admin/safety-cases/{caseId}/evidence-timeline`
- `POST /api/v1/admin/safety-cases/{caseId}/decision`
- `GET /api/v1/admin/appeals`
- `POST /api/v1/admin/appeals/{appealId}/resolve`
- `GET /api/v1/admin/risk-signals`
- `GET /api/v1/admin/finance/payouts/{payoutId}`
- `GET /api/v1/admin/finance/ledger/{transactionId}`
- `GET /api/v1/admin/audit-log`

Todas las operaciones Admin validan que el actor interno exista, tenga `role=ADMIN` y `account_status=ACTIVE`.

## Finanzas

Admin puede observar efectos financieros, pero no mutarlos desde estos endpoints. Los montos son siempre los valores reales de la transacción persistida. No se usan precios de ejemplo ni montos fijos inventados.

## Datos que no expone este bloque

- PAN/CVV.
- Access/refresh tokens.
- Documentos KYC crudos.
- Biometría cruda.
- Video privado grabado de sesiones Online.
- Risk signals como reputación pública.

## CI

`.github/workflows/admin-review-integration.yml` levanta PostgreSQL 16 + PostGIS, carga schema V1.0 y migraciones V1.1-V1.5, inicia el runtime Spring Boot y prueba:

- S5 crea HumanReviewQueue.
- ADMIN puede claim.
- Evidence Timeline es legible.
- decisión humana S5 se registra.
- AdminAction y AuditEvent quedan persistidos.
- finanzas Admin rechazan mutaciones con HTTP 405.

## Pendientes posteriores

- UI Admin productiva.
- Integración con proveedor KYC real sin exponer documentos crudos.
- Política operacional de staffing/escalamiento de Safety.
- Notificaciones internas de cola y SLA operativos.
- Integración con casos reales de incidentes y payout holds.
- Autorización OAuth2 de scopes/claims Admin del proveedor de identidad definitivo.
