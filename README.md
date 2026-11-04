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

## Base de datos (v2, fase 4): Supabase

La persistencia segura (usuarios y códigos de activación) usa **Supabase (Postgres)**.
Son opcionales: sin credenciales la app funciona en modo FREE y las pruebas contra la
base real se omiten automáticamente.

Variables de entorno (nunca hardcoded; ver `docs/criptografia.md`):

```powershell
$env:SUPABASE_DB_URL  = "jdbc:postgresql://db.<proyecto>.supabase.co:5432/postgres?sslmode=require"
$env:SUPABASE_DB_USER = "postgres"
$env:SUPABASE_DB_PWD  = "<password del proyecto>"
```

CLI adicional para pruebas: `system properties` `mp3converter.db.url/user/pwd`.

## Comandos
```bash
./gradlew test              # unitarios de todos los módulos (rápidos)
./gradlew check             # unitarios + integración (ffmpeg real) + db-integration (skip sin SUPABASE_DB_*)
./gradlew dbIntegrationTest # SOLO pruebas contra Supabase real (tag db-integration)

./gradlew :app-v1-desktop:jpackageImage   # genera el .exe de la v1
./gradlew :app-v2-freemium:jpackageImage  # genera el .exe de la v2 (freemium)
./build-exe.bat        # equivalente, vía script (variante por defecto: v1)
./build-exe.ps1 -App v2    # otras variantes cuando existan (v2 → app-v2-freemium)
```

El ejecutable queda en `app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe`
(autocontenido: no requiere Java instalado).

## Estructura del repositorio
```
mp3Converter/
├── core/                  ← lib compartida (conversión + licencias + DB cifrada)
├── app-v1-desktop/        ← Proyecto 1: MP3Converter Desktop v1.0.0 (JavaFX + jpackage)
├── app-v2-freemium/       ← Proyecto 2: v2.0.0 pay-to-pro
├── app-v3-saas/           ← Proyecto 3: v3.0.0 SaaS (futuro)
├── docs/                  ← PLAN_MAESTRO.md, criptografia.md
├── parked/                ← código aparcado (no entra en el build)
├── build-exe.bat / build-exe.ps1   ← empaquetado por variante
├── settings.gradle.kts
└── build.gradle.kts       ← raíz (multi-módulo Gradle)
```

## Documentación adicional
- Plan de acción completo (fases, características, pruebas y riesgos): `docs/PLAN_MAESTRO.md`.

## Licencia
MIT (ver `LICENSE`).
