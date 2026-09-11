# TiempoJusto P2.1 UX / Wireframes V1

Fuente funcional: Documento Maestro V1.7.

Figma editable: https://www.figma.com/design/xnWgItVIbdxLPmy9i6oJNk

## Objetivo

Traducir los flujos funcionales ya congelados a wireframes móviles editables sin cambiar reglas de producto.

## Pantallas cubiertas

1. Onboarding y KYC
2. Home / Discovery
3. Mapa aproximado
4. Perfil público
5. Proposal
6. Disponible Ahora
7. Meta Ahora
8. Auction
9. Live asociado a Auction
10. Ganador / confirmación inmediata
11. Presencial: ruta, Arrival y Handshake
12. Online Ahora
13. PAID_ACTIVE y reconexión
14. Extensión
15. Wallet / Payout
16. Safety / reportes
17. Rating y cierre
18. Overview de flujo P2.1

## Reglas V1.7 reflejadas

- MVP inmediato, sin agenda futura.
- Proposal no reserva ni crea Bid.
- Meta Ahora usa una sola Proposal máxima compatible, no suma usuarios.
- Auction base 15 min, anti-sniping y Ganar Ahora.
- Llegada no equivale a Handshake ni inicia billing.
- Handshake presencial bilateral antes de FREE de 5 min.
- Online: join 3 min, FREE_ONLINE 2 min, cámara obligatoria, confirmación bilateral 30 s, microcorte 5 s, reconexión 2 min.
- Recuperar media no reactiva billing sin aceptación bilateral.
- Live sigue separado de Auction; una caída de video no pausa la Auction.
- Wallet distingue PENDING y AVAILABLE y muestra hold de 60 min.
- Safety Exit termina sesión y cobro inmediatamente.
- Ubicación pública aproximada; GPS exacto no se muestra en discovery.

## Decisiones de diseño V1, no reglas funcionales

Los colores, tipografía, jerarquía visual, navegación inferior, espaciados y copy corto son decisiones de UX V1 para prototipado y pueden iterarse sin cambiar la lógica de negocio.

## Pendientes UX

- Prototipo navegable con interacciones entre pantallas.
- Estados vacíos, error, loading, timeout y offline.
- Accesibilidad WCAG y contraste final.
- Microcopy legal y de consentimiento final.
- Responsive web/tablet si se confirma alcance multiplataforma.
- Test de usabilidad con usuarios piloto.
