# WLive

Prototipo Android para experimentar con transmisión de video en vivo usando menos datos.

## Objetivo

WLive busca medir cuánto puede reducirse el bitrate de un LIVE manteniendo una calidad visual útil. La primera etapa no se conecta a TikTok: primero valida cámara, modos de calidad y medición local.

## v0.1.0

- Vista previa de cámara frontal con CameraX.
- Tres modos de prueba: Calidad, Ahorro y Ultra Ahorro.
- Estructura preparada para integrar MediaCodec H.264.
- Base para añadir contador real de bytes y RTMP en versiones posteriores.

## Roadmap

- v0.2.0: encoder H.264 con MediaCodec y bitrate configurable.
- v0.3.0: contador real de MB enviados y comparación entre modos.
- v0.4.0: bitrate adaptativo según movimiento/escena.
- v0.5.0: salida RTMP de prueba.
- v0.6.0+: evaluación de compatibilidad con plataformas LIVE.

## Principio del proyecto

No intenta modificar TikTok ni interceptar su tráfico. WLive genera su propio stream compatible y mide cuánto puede ahorrar antes de conectarlo a un destino externo.
