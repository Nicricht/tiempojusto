# TiempoJusto Media Core V1

Implementación ejecutable de P1.4 WebRTC/Media para Java 21, desacoplada de cualquier proveedor real.

## Fuente funcional

Documento Maestro V1.7:

- Online: JOIN_WINDOW 3 min.
- FREE_ONLINE 2 min se inicia solo con ambos conectados y video válido.
- Cámara válida obligatoria al inicio y durante PAID_ACTIVE.
- Decisión bilateral de pago en 30 s pertenece al dominio de sesión.
- Microcorte técnico: 5 s.
- Interrupción confirmada pausa desde el último media/heartbeat válido.
- Reconexión Online: 2 min y reanudación solo con aceptación bilateral.
- Videollamada privada sin grabación por defecto.
- Live: solo asociado a Auction, reconexión 2 min y la Auction continúa aunque el video falle.
- Live: sin replay público y sin grabación completa permanente por defecto.

## Diseño

`WebRtcPort` es el puerto agnóstico. `MockWebRtcPort` existe únicamente para desarrollo y contract tests. Un adaptador real deberá resolver creación de rooms, tokens y TURN sin filtrar secretos del proveedor al dominio.

`OnlineMediaRoom` no calcula dinero. Solo produce verdad técnica de media. El dominio de Session sigue siendo la autoridad para PAID_ACTIVE, segundos facturables y liquidación.

La recuperación de conectividad cambia el media state a `RECOVERED_AWAITING_BILATERAL_RESUME`; no reactiva billing. El backend debe recibir aceptación de HOST y BIDDER antes de regresar a `PAID_ACTIVE`.

El mute de audio aislado no se interpreta como corte de media. V1.7 lo excluye como causa automática de pausa.

## Decisiones técnicas V1, no reglas nuevas de producto

- `MockWebRtcPort` emite credenciales TURN de 10 minutos solo para tests.
- No se congela una frecuencia exacta de heartbeat. El proveedor/adaptador real debe entregar señales suficientemente robustas para respetar la tolerancia funcional de 5 s.
- No se selecciona proveedor WebRTC/TURN, región ni topología SFU/MCU/P2P.

## Ejecutar

```bash
cd backend/media
bash run-tests.sh
```

Resultado esperado: `PASS: 17/17 Media contract tests`.
