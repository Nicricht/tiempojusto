# TiempoJusto MVP Production V1

Estado: ALCANCE CONGELADO PARA IMPLEMENTACIÓN

Fuente funcional: Documento Maestro V1.7.
Fuente técnica de persistencia e invariantes físicas: schema PostgreSQL/PostGIS + migraciones vigentes.
Golden Path de referencia: `.github/workflows/mvp-sandbox-golden-path.yml`.

## 1. Objetivo

Convertir el sistema actualmente validado en sandbox en un producto utilizable de extremo a extremo por usuarios reales, sin ampliar el producto antes de demostrar que el flujo principal funciona con proveedores reales, staging y frontend conectado.

La meta de V1 es una sola:

> Un HOST y un BIDDER pueden completar una transacción ONLINE inmediata desde registro/KYC hasta payout, con cobro por tiempo, reconexión segura, ledger auditable y Safety operativo.

Este documento NO reemplaza ni reescribe las reglas de negocio V1.7. Solo congela el alcance de lanzamiento y el orden de implementación.

## 2. Principio de alcance

Hasta completar este MVP no se agregan nuevas reglas de producto salvo que sean necesarias para corregir una contradicción, un bloqueo regulatorio/operativo o una vulnerabilidad. Si una regla no existe en V1.7, no se inventa dentro de la implementación.

Toda decisión nueva que cambie dinero, edad, tiempos, reputación, sanciones, privacidad, payouts, subastas o elegibilidad requiere decisión explícita de producto.

## 3. Flujo incluido en V1

### 3.1 Identidad y acceso

- Registro e inicio de sesión con proveedor OAuth2/OIDC de producción.
- Vinculación provider-neutral `issuer + subject` al usuario interno.
- KYC con proveedor real en sandbox primero y producción después del gate correspondiente.
- Solo el resultado mínimo necesario queda persistido. No se almacenan documentos ni biometría cruda del proveedor.
- Verificación legal 18+ por KYC.
- Reglas públicas de edad y elegibilidad continúan exactamente según V1.7.

### 3.2 Perfil y descubrimiento

- Perfil HOST.
- Disponibilidad ONLINE inmediata.
- Discovery suficiente para llegar a un perfil y comenzar el flujo.
- Sin introducir lógica nueva de ranking o recomendación para el lanzamiento.

### 3.3 Proposal y Auction

- Proposal ONLINE.
- Auction de 15 minutos.
- Bid NORMAL con reserva financiera.
- Pozo y `server_sequence` server-authoritative.
- Anti-sniping exactamente 2 minutos y sin límite de extensiones.
- Close Now / Ganar Ahora según reglas vigentes.
- Confirmación del Winner durante 3 minutos.
- El protocolo de reserva debe impedir que una Bid válida quede descubierta.

### 3.4 Sesión ONLINE

- Ventana simultánea de 3 minutos para entrar tras adjudicación.
- Cámara obligatoria para ambos al iniciar y durante `PAID_ACTIVE`.
- `FREE_ONLINE` de 2 minutos.
- Aceptación bilateral dentro de 30 segundos antes de iniciar cobro.
- Microcorte con tolerancia técnica de 5 segundos.
- Si la interrupción supera 5 segundos, billing se pausa retroactivamente al último media/heartbeat válido.
- Reconexión de 2 minutos.
- Recuperar conexión NO reanuda cobro automáticamente.
- Reanudación solo con nueva aceptación bilateral.
- Al vencer reconexión, la sesión termina y se liquida.
- No existe grabación persistente privada de la sesión.

### 3.5 Finance

- Reserva/autorización financiera antes de exposición de fondos.
- Captura final conforme al consumo válido.
- Ledger de doble entrada.
- Split de sesión/extensión ONLINE 80% HOST / 20% plataforma.
- Payout con hold mínimo de 60 minutos.
- `PENDING`, `AVAILABLE` y holds objetivos continúan siendo estados/invariantes internos obligatorios.
- Reconciliación con proveedor y manejo idempotente de webhooks.
- La liquidación proporcional con fracción no entera de CLP continúa bloqueada como `PENDING_ROUNDING_POLICY` hasta que producto defina la regla exacta. No se inventa redondeo.

### 3.6 Safety mínimo de lanzamiento

- Reportar y bloquear continúan siendo acciones distintas.
- Reportes, evidencia, cola de revisión humana, sanciones vigentes S0-S5 y apelaciones.
- S5 requiere el flujo humano ya definido.
- Solo usuarios verificados por KYC pueden afectar reputación conforme a V1.7.
- Safety puede terminar una sesión cuando corresponda según las reglas existentes.
- Holds financieros objetivos deben ser auditables.

## 4. Fuera de alcance del MVP Production V1

Se posponen para una fase posterior al piloto:

- Presencial como recorrido productivo de lanzamiento.
- Live completo y Live Ticket 70/30.
- Expansión internacional y múltiples monedas.
- Agenda futura si no forma parte del MVP inmediato vigente.
- Motor sofisticado de recomendaciones/IA.
- Nuevas reglas de reputación.
- Nuevas categorías de monetización.
- Microservicios como arquitectura de despliegue.
- Cualquier funcionalidad que no sea necesaria para completar, operar, observar o proteger el Golden Path ONLINE.

El código ya existente para funcionalidades pospuestas no se elimina por este documento. Simplemente no bloquea el lanzamiento de V1.

## 5. Arquitectura de lanzamiento

TiempoJusto continúa como modular monolith Java 21/Spring Boot con PostgreSQL/PostGIS.

Módulos lógicos prioritarios:

- Identity/KYC
- Market/Proposal
- Auction
- Appointment/Session
- Media/WebRTC
- Finance/Ledger/Payout
- Safety/Admin
- Platform/Audit/Outbox

No se divide a microservicios para el MVP. Una separación futura requiere evidencia de necesidad operativa, escalabilidad o ownership, no preferencia estética.

## 6. Gates obligatorios antes de dinero real

### Gate A - Baseline reproducible

- `main` verde.
- Golden Path sandbox verde.
- Schema + migraciones aplicables desde cero.
- Ledger balanceado.
- Invariantes de payout y session status verificadas.

Estado actual: CUMPLIDO en CI para el corte sandbox integrado.

### Gate B - Payment sandbox real

- Proveedor de pago elegido y configurado solo con credenciales sandbox.
- Reserve/authorize, capture, release/refund y webhook real probados.
- Idempotencia probada.
- Webhook duplicado, retrasado y fuera de orden probado.
- Reconciliación provider -> ledger probada.
- Ninguna Bid puede quedar financieramente descubierta.
- No se habilita dinero real en este gate.

### Gate C - KYC sandbox real

- Flujo create session -> callback/webhook -> verified/rejected.
- Firma del webhook validada.
- Mapeo de 18+ probado.
- No persistencia de documento/biometría cruda demostrada.
- Reintentos e idempotencia probados.

### Gate D - WebRTC/TURN real

- Dos navegadores reales pueden entrar a la sala.
- Cámara obligatoria se refleja en estado de sesión.
- Microcorte <=5 s no pausa.
- Corte >5 s produce reconexión y pausa retroactiva.
- Recuperación no reinicia billing.
- Resume bilateral reinicia billing.
- Timeout de 2 minutos finaliza correctamente.
- Sin grabación persistente privada.

### Gate E - Frontend conectado

Recorrido mínimo navegable:

1. Registro/login.
2. KYC.
3. Discovery/perfil HOST.
4. Proposal.
5. Auction en tiempo real.
6. Winner y confirmación.
7. Sala ONLINE.
8. FREE_ONLINE.
9. Consentimiento bilateral.
10. PAID_ACTIVE + reconnect.
11. Finalización.
12. Wallet/Payout.
13. Reportar/bloquear.

El frontend no duplica reglas críticas. Tiempos, elegibilidad, dinero y estados autoritativos vienen del backend.

### Gate F - Staging

- HTTPS.
- Backend, frontend y PostgreSQL/PostGIS desplegados.
- Secrets fuera del repositorio.
- Migraciones automáticas/controladas.
- Health/readiness.
- Logs estructurados.
- Métricas mínimas.
- Alertas mínimas.
- Backups y prueba de restore.
- Golden Path contra staging con proveedores sandbox.

### Gate G - Seguridad operativa

- Rate limiting en superficies sensibles.
- Validación OAuth/JWT real.
- Webhooks firmados.
- CSRF/CORS según arquitectura del frontend.
- Headers y cookies seguros si corresponde.
- Dependencias escaneadas.
- Secrets scanning.
- Auditoría de acciones administrativas.
- Prueba de abuso básica de auth, Auction, payment callbacks y Safety.

### Gate H - Piloto cerrado

- Hosts controlados.
- Soporte humano disponible.
- Límites operacionales conservadores.
- Reconciliación financiera diaria durante piloto.
- Incidentes revisados manualmente.
- No expansión internacional durante el piloto inicial.

## 7. Métricas que deciden el siguiente paso

Durante beta/piloto se mide como mínimo:

- Registro -> KYC completado.
- KYC -> perfil/discovery útil.
- Visita de perfil -> Proposal/Auction.
- Auction -> Bid válida.
- Bid -> adjudicación.
- Adjudicación -> sesión iniciada.
- Sesión iniciada -> sesión completada.
- Sesión completada -> settlement correcto.
- Settlement -> payout AVAILABLE.
- Tasa de fallos por proveedor.
- Tasa de reconnect.
- Refunds/chargebacks/disputas.
- Reportes Safety.
- Repetición de uso.

El objetivo del piloto no es maximizar features. Es demostrar que el flujo central puede repetirse de forma segura y comprensible.

## 8. Definition of Done del MVP Production V1

El MVP Production V1 se considera técnicamente listo para piloto cuando:

- Los Gates A-G están cumplidos.
- El Golden Path sandbox sigue verde.
- Existe un Golden Path staging con proveedores sandbox reales.
- Ningún test requiere modificar reglas de producto para pasar.
- No hay estados financieros imposibles detectados.
- Ledger cierra en doble entrada.
- Payout respeta hold mínimo y holds objetivos.
- Online reconnect respeta los tiempos V1.7.
- El frontend completa el recorrido sin intervención de base de datos.
- Admin puede diagnosticar usuarios, KYC, Auction, Session, ledger, payout y reportes sin mutar manualmente datos financieros.
- `PENDING_ROUNDING_POLICY` continúa bloqueando cualquier caso no definido.

## 9. Orden de ejecución aprobado por este corte

1. Baseline y limpieza de backlog duplicado.
2. Payment sandbox real.
3. KYC sandbox real.
4. WebRTC/TURN real.
5. Frontend funcional conectado al Golden Path.
6. Admin UI mínima productiva.
7. Staging + observabilidad + backups.
8. Security hardening y pruebas E2E ampliadas.
9. Piloto cerrado.
10. Solo después del piloto, reabrir alcance para Presencial, Live, internacionalización y expansión de producto.

## 10. Regla de cambio

Cualquier PR que agregue alcance fuera de este documento debe responder explícitamente:

- ¿Bloquea el Golden Path ONLINE?
- ¿Bloquea seguridad, cumplimiento, pagos, operación o piloto?
- ¿Está respaldado por una regla existente V1.7?

Si las respuestas son no, no entra al MVP Production V1.
