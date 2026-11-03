# mp3Converter — Portfolio multi-proyecto

Un solo repositorio con 3 proyectos Java de portfolio. Todos comparten la lógica de
conversión (módulo `core`, ffmpeg externo vía ProcessBuilder):

| Módulo | Proyecto | Versión | Estado |
|--------|----------|---------|--------|
| `app-v1-desktop` | MP3Converter Desktop | 1.0.0 | Activo (estado histórico, sin cambios) |
| `app-v2-freemium` | MP3Converter Freemium (92 kbps, watermark, compra mock + activación Pro) | 2.0.0 | En desarrollo |
| `app-v3-saas` | MP3Converter SaaS (suscripción mensual/anual) | 3.0.0 | Borrador del plan |

## Requisitos
- **Java 21** (JDK: <https://adoptium.net/>) para compilar y ejecutar.
- **ffmpeg** en el PATH del sistema (`winget install ffmpeg`) para convertir el audio.

## Comandos
```bash
./gradlew test        # unitarios de todos los módulos (rápidos)
./gradlew check       # unitarios + integración (ffmpeg real) de todos los módulos

./gradlew :app-v1-desktop:jpackageImage   # genera el .exe de la v1
./build-exe.bat        # equivalente, vía script (variante por defecto: v1)
./build-exe.ps1 -App v2    # otras variantes cuando existan (v2 → app-v2-freemium)
```

El ejecutable queda en `app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe`
(autocontenido: no requiere Java instalado).

## Estructura del repositorio
```
mp3Converter/
├── core/                  ← lib compartida (lógica de conversión)
├── app-v1-desktop/        ← Proyecto 1: MP3Converter Desktop v1.0.0 (JavaFX + jpackage)
├── app-v2-freemium/       ← Proyecto 2: v2.0.0 pay-to-pro (próximo)
├── app-v3-saas/           ← Proyecto 3: v3.0.0 SaaS (futuro)
├── docs/                  ← PLAN_MAESTRO.md y documentación
├── parked/                ← código aparcado (no entra en el build)
├── build-exe.bat / build-exe.ps1   ← empaquetado por variante
├── settings.gradle.kts
└── build.gradle.kts       ← raíz (multi-módulo Gradle)
```

## Documentación adicional
- Plan de acción completo (fases, características, pruebas y riesgos): `docs/PLAN_MAESTRO.md`.

## Licencia
MIT (ver `LICENSE`).
