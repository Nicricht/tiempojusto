# TiempoJusto Finance Core V1.0

Implementación ejecutable del hito **Ledger financiero + PaymentPort Mock** para Java 21.

## Alcance

- `PaymentPort` agnóstico del proveedor.
- `MockPaymentPort` determinista con reserva, ajuste, liberación, captura, refund, payout y dispute.
- Idempotencia estricta por operación.
- Fallos simulados de proveedor sin aleatoriedad.
- Ledger de doble entrada, append-only a nivel de API.
- Correcciones mediante transacciones compensatorias.
- Settlement de sesión/extensión 80/20 sobre monto realmente generado.
- Ticket Live 70/30.
- `PENDING -> AVAILABLE` después de 60 minutos.
- `HELD_FOR_REVIEW` ante incidencia objetiva.
- No-show HOST pre-capture: liberación 100% y HOST $0.
- Chargeback de HOST cumplidor no crea deuda automática.
- Recuperación por fraude confirmado exige referencia objetiva y se registra en Ledger.

## Ejecutar pruebas

Linux/macOS:

```bash
cd backend/finance
./run-tests.sh
```

Windows PowerShell:

```powershell
cd backend/finance
.\run-tests.ps1
```

## Límites intencionales

Este módulo NO selecciona el proveedor real, NO define tarifas bancarias, NO promete tiempos de retiro bancario, NO resuelve impuestos y NO inventa una política de chargeback tardío. Esas decisiones siguen dependiendo de validación externa/proveedor conforme a V1.7.

La división de CLP enteros usa `floor` para la participación HOST y asigna cualquier peso residual a plataforma. Esto es una decisión técnica provisional porque V1.7 reconoce que las reglas monetarias de redondeo definitivas aún deben cerrarse.
