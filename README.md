# mp3Converter — Portfolio multi-proyecto

Un solo repositorio Gradle con 3 proyectos Java del mismo negocio de audio, cada uno
en su fase de evolución. Todos comparten la lógica de conversión (módulo `core`,
ffmpeg externo vía ProcessBuilder):

| Módulo | Proyecto | Versión | Estado |
|--------|----------|---------|--------|
| `app-v1-desktop` | MP3Converter Desktop | 1.0.0 | Activo (estado histórico, congelado) |
| `app-v2-freemium` | MP3Converter Freemium — pay-to-pro | 2.0.0 | **Activo (Fases 0–6 completas)** |
| `app-v3-saas` | MP3Converter SaaS (suscripción) | 3.0.0 | Borrador (`docs/saas/ARCHITECTURE.md`, Fase 7) |
| `backend-saas` | Backend SaaS (Spring Boot 3.x) | 3.0.0 | **Stub vacío** en el build (Fase 7) |

## Requisitos
- Para compilar: **Java 21** (JDK: <https://adoptium.net/>).
- Para convertir: **ffmpeg** en el PATH (`winget install ffmpeg`).
- Los ejecutables `.exe` (jpackage) son autocontenidos: **no requieren Java instalado**.

## Proyecto 1 — MP3Converter Desktop v1.0.0
Conversor WAV/AIFF/FLAC/M4A/OGG/WMA a MP3 con VBR de alta calidad (`-q:a 2`),
sin marca de agua ni restricciones. Es el estado histórico del producto: se
construye y ejecuta, pero no recibe cambios.

```bash
./gradlew :app-v1-desktop:jpackageImage
# exe: app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe
```

## Proyecto 2 — MP3Converter Freemium v2.0.0 (pay-to-pro)
El modelo freemium demostrado end-to-end:

**Free (sin licencia):**
- Calidad fija **92 kbps CBR** (`-b:a 92k`, nunca VBR).
- Marca de agua audible **"mp3Converter"** al inicio y al final de cada MP3.
- Los `.opus` se listan pero requieren la versión Pro (ffmpeg nunca se invoca
  para ellos) con CTA "Comprar versión Pro".

**Pro (con licencia):**
- VBR completo (`-q:a 2`), sin watermark, `.opus` habilitado.

### Guía de activación
1. "Comprar versión Pro" → formulario con validaciones exactas (tarjeta 16
   dígitos + Luhn, CVV 3, `mm/AAAA` no vencido, nombres solo letras, dirección ≤70
   con `#`, teléfono 10 dígitos + dropdown de país, correo).
2. Pasarela **mock** (aprueba siempre, latencia simulada; los datos de tarjeta
   viven solo en memoria y en logs solo `**** 1234`).
3. Al aprobar se genera la **clave de activación** (persistida cifrada en
   Supabase si hay credenciales; efímera en modo demo sin BD).
4. "Copiar al portapapeles" (el cuadro permanece abierto) →
   **"Activar contrato Pro"** → se escribe `mp3converter.lic` (binario MPCL con
   AES-256-GCM + HMAC-SHA256) junto al `.exe`, aparece el mensaje de
   agradecimiento y la app pasa a **PRO sin reiniciar**.
5. Al reiniciar, la app lee la `.lic` de su carpeta y abre en PRO.
6. Borrar la `.lic` devuelve la app a FREE.

La licencia es **por carpeta** (multi-instancia): dos carpetas de exe pueden
tener estados distintos sin interferir entre sí.

### Arquitectura v2
```
core (Java, sin JavaFX)
├── conversion:  Mp3ConversionService + QualityPolicy (92k CBR / VBR) + watermark
├── license:     LicenseCodec (MPCL binario), LicenseVerifier, LicenseController,
│                FieldCrypto (PBKDF2 sal=correo, pepper=apellido), ActivationCodeGenerator
├── payment:     PaymentValidator (reglas exactas 2.5), PaymentGateway + MockPaymentGateway,
│                PaymentFlow -> PurchaseOutcome (perfil en memoria, jamás persistido)
└── db:          DatabaseConfig (env SUPABASE_DB_*), UserRepository, ActivationRepository
                  (hashes HMAC + AES; jamás plaintext en BD)

app-v2-freemium (JavaFX)
└── FeatureGate (tier desde .lic, hot flip), ControllerV2 (fluye compra→activación),
    PaymentDialogV2 / ActivationDialogV2 (TextFormatters sin carets rotos)
```

## Base de datos: Supabase (opcional)
La persistencia (usuarios + códigos de activación cifrados) usa **Supabase
Postgres**. Sin credenciales la app funciona y guarda el código con marca
"efímero" (no se recupera después). Las pruebas contra la base real se omiten
automáticamente si no hay variables:

```powershell
$env:SUPABASE_DB_URL  = "jdbc:postgresql://db.<proyecto>.supabase.co:5432/postgres?sslmode=require"
$env:SUPABASE_DB_USER = "postgres"
$env:SUPABASE_DB_PWD  = "<password del proyecto>"
```

Variante por system properties para pruebas: `mp3converter.db.url/user/pwd`.
Detalle criptográfico completo: `docs/criptografia.md`.

## Comandos
```bash
./gradlew test              # unitarios de todos los módulos (rápidos)
./gradlew check             # unitarios + integración (ffmpeg real) + db-integration
./gradlew dbIntegrationTest # SOLO pruebas contra Supabase real (tag db-integration)

./gradlew :app-v2-freemium:jpackageImage   # .exe v2 (autocontenido, sin Java)
./build-exe.bat                            # variante por defecto (v1)
./build-exe.ps1 -App v2                    # empaquetado por variante (v1|v2)
```

Ejecutables:
- v1: `app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe`
- v2: `app-v2-freemium\build\jpackage\mp3ConverterFree\mp3ConverterFree.exe`
  (la `mp3converter.lic` vive junto a este exe; borrarla = volver a FREE)

Nota: `jpackageImage` borra la carpeta del exe — si la `.lic` existía, hay que
volver a activar (o no incluirla para probar el flujo de compra desde cero).

## Estructura del repositorio
```
mp3Converter/
├── core/                  ← lib compartida (conversión + licencias + pago + DB cifrada)
├── app-v1-desktop/        ← Proyecto 1: MP3Converter Desktop v1.0.0 (JavaFX + jpackage)
├── app-v2-freemium/       ← Proyecto 2: v2.0.0 pay-to-pro (compra→activación→PRO)
├── backend-saas/          ← Proyecto 3: stub Spring Boot del SaaS (Fase 7, vacío)
│                            + docs/saas/ARCHITECTURE.md (modelo de suscripción)
├── docs/                  ← PLAN_MAESTRO.md, criptografia.md, saas/
├── parked/                ← código aparcado (no entra en el build)
├── build-exe.bat / build-exe.ps1   ← empaquetado por variante (v1|v2)
├── settings.gradle.kts
└── build.gradle.kts       ← raíz (multi-módulo Gradle)
```

## Documentación adicional
- Plan de acción completo (fases, características, pruebas y riesgos):
  `docs/PLAN_MAESTRO.md`.
- Diseño criptográfico de la Fase 4/5 (por qué sal=correo y pepper=apellido,
  formato MPCL, hashes en BD): `docs/criptografia.md`.

## Licencia
MIT (ver `LICENSE`).
