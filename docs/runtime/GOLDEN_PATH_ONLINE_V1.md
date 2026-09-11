# TiempoJusto Golden Path Online V1

## Estado

Validado por el workflow `Runtime Integration`: PostgreSQL/PostGIS, build Spring Boot, health del runtime y ejecución HTTP del Golden Path sandbox completan correctamente en CI.

## Objetivo

Probar una primera vertical slice ejecutable que conecte reglas de Proposal, Auction, reservas financieras, WebRTC privado, estado Online, settlement 80/20, hold de 60 minutos y payout.

Este harness existe solo bajo perfiles `dev`, `test` y `ci`. No es un endpoint de producción.

## Flujo probado

`KYC sandbox verified-adult -> Proposal -> Auction Close Now -> funds reservation -> Online private room -> FREE_ONLINE 2m -> bilateral paid acceptance -> PAID_ACTIVE -> settlement -> hold 60m -> payout`

## Reglas preservadas

- Proposal >= CLP 10.000 y múltiplo de CLP 5.000.
- Auction usa financiación válida antes de cerrar.
- Sala Online privada con grabación persistente desactivada.
- Cámara válida para ambos antes de FREE_ONLINE.
- FREE_ONLINE exactamente 2 minutos.
- Pago solo después de aceptación bilateral dentro de la ventana correspondiente.
- Session settlement 80% HOST / 20% TiempoJusto.
- Earning permanece PENDING durante el hold de 60 minutos.
- Payout solo después de pasar a AVAILABLE.

## Deliberadamente no resuelto aquí

Este harness no selecciona proveedor real de KYC, pagos/payouts ni WebRTC/TURN. Usa los adapters mock ya existentes.

Tampoco inventa la regla de redondeo CLP para una sesión proporcional que termine en segundos parciales. Para evitar crear una regla de producto nueva, el escenario V1 consume la duración pagada completa, por lo que el monto generado coincide exactamente con el monto reservado.

## Escenario CI de referencia

- Amount: CLP 60.000
- Online duration: 30 min
- Gross generated: CLP 60.000
- HOST 80%: CLP 48.000
- TiempoJusto 20%: CLP 12.000
- Payout sandbox: CLP 48.000 después del hold

El workflow `Runtime Integration` llama el endpoint interno y falla si cualquiera de esos invariantes no se cumple.

## Siguiente corte

Sustituir el harness único por application services y endpoints reales, con persistencia transaccional del Golden Path y auth/scopes, empezando por registro/KYC sandbox, Proposal, Auction y Online. Después, reemplazar adapters sandbox por proveedores reales de forma independiente.
