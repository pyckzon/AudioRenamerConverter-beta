# mp3Converter — Portfolio multi-proyecto

> **English version:** [README_EN.md](README_EN.md)

Un solo repositorio Gradle con 3 proyectos Java del mismo negocio de audio, cada
uno en su fase de evolución. Todos comparten la lógica de conversión (módulo
`core`, ffmpeg externo vía ProcessBuilder).

## ⚠️ Aviso: proyecto de portfolio

Este repositorio es un **proyecto de portfolio con fines de práctica personal**.
No es software de producción ni un producto comercial:

- La pasarela de pago es **MOCK** (simulada): aprueba siempre y **no procesa
  dinero real**. Ningún dato de tarjeta se guarda jamás — ni en disco, ni en
  base de datos, ni en logs.
- Las claves de activación, licencias y suscripciones existen para demostrar el
  diseño de software, no para cobrar por el producto.
- No lo uses para gestionar pagos reales ni como base de un producto sin
  adaptarlo primero (pasarela real, términos, privacidad, etc.).

## ¿Qué hace el proyecto?

Es un **conversor de audio a MP3 para escritorio (Windows/JavaFX)** que acepta
WAV/AIFF/FLAC/M4A/OGG/WMA/.opus, delega la conversión a **ffmpeg** y controla la
calidad de salida. Sobre esa base común se construyen tres "versiones de
producto" que demuestran tres modelos de negocio distintos:

| Módulo | Proyecto | Versión | Modelo | Estado |
|--------|----------|---------|--------|--------|
| `app-v1-desktop` | MP3Converter Desktop | 1.0.0 | Gratis, sin restricciones | Congelado (histórico) |
| `app-v2-freemium` | MP3Converter Freemium | 2.0.0 | Freemium **pay-to-pro** (licencia local) | Completo |
| `app-v3-saas` + `backend-saas` | MP3Converter SaaS | 3.0.0 | **Suscripción** (backend Spring Boot) | Completo |

## Propósito y cambios entre versiones

### v1.0.0 — Desktop (congelado)
- **Propósito:** el punto de partida histórico: un conversor completo y sin
  restricciones (VBR `-q:a 2`, sin marca de agua).
- **Qué demuestra:** conversión real con ffmpeg, UI JavaFX, empaquetado
  `jpackage` autocontenido (sin Java instalado).
- **Estado:** no recibe cambios; sirve como referencia del "antes".

### v2.0.0 — Freemium pay-to-pro
- **Cambios respecto a v1:** introduce el modelo freemium sobre la misma UI:
  - **Free:** calidad fija **92 kbps CBR** (nunca VBR), marca de agua audible
    "mp3Converter" al inicio y fin de cada MP3, y los `.opus` se listan pero
    requieren Pro.
  - **Pro:** VBR completo, sin watermark, `.opus` habilitado.
  - Flujo completo **compra → activación**: formulario de pago con validaciones
    exactas (Luhn, CVV, fecha no vencida, nombres, dirección, teléfono),
    pasarela mock, **clave de activación** y archivo de licencia
    `mp3converter.lic` (binario MPCL cifrado AES-256-GCM + HMAC-SHA256).
  - **PRO en caliente** (sin reiniciar) y licencia **por carpeta**
    (multi-instancia: cada copia del exe tiene su propio estado).
  - Persistencia opcional en **Supabase** con cifrado: `email_hash` (HMAC),
    campos AES-256-GCM; **jamás plaintext** en la base de datos.

### v3.0.0 — SaaS por suscripción
- **Cambios respecto a v2:** la licencia local `.lic` desaparece; el tier Pro
  ahora lo define una **suscripción activa en un backend real**:
  - Backend **Spring Boot 3.x** (`backend-saas`): registro/login **JWT**,
    suscripciones, checkout con página **mock** servida por el propio backend
    (su botón "Pagar" dispara el webhook, imitando el checkout hosted de una
    pasarela real), emisión de token de licencia y **heartbeat**.
  - En la base de datos el token de licencia se guarda **solo como HMAC**;
    passwords con BCrypt; el email solo como hash (el original cifrado AES).
  - App (fork de v2): diálogo de suscripción (crear cuenta → checkout en el
    navegador → PRO en caliente), **daemon de heartbeat** que re-verifica cada
    ~24 h y **gracia offline de 7 días** si el backend no responde.
  - La tarjeta **nunca pasa por la app**: el checkout se hace en el navegador.
  - Verificado contra **Supabase real** (test de integración `db-integration`)
    y validado en contenedor Docker de extremo a extremo.

## Cómo compilar y ejecutar

**Requisitos:** Java 21 (JDK: <https://adoptium.net/>) para compilar;
**ffmpeg** en el PATH para convertir (`winget install ffmpeg`). Los `.exe` de
jpackage son autocontenidos: **no requieren Java instalado**.

### v1 — Desktop
```bash
./gradlew :app-v1-desktop:jpackageImage
# exe: app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe
# o en desarrollo: ./gradlew :app-v1-desktop:run
```

### v2 — Freemium
```bash
./gradlew :app-v2-freemium:jpackageImage
# exe: app-v2-freemium\build\jpackage\mp3ConverterFree\mp3ConverterFree.exe
# o en desarrollo: ./gradlew :app-v2-freemium:run
```
- Sin licencia arranca en **Free**. "Comprar versión Pro" → formulario →
  pasarela mock → **copiar clave** → "Activar contrato Pro" → PRO al instante.
- La `mp3converter.lic` vive junto al exe; **borrarla = volver a Free**.
- Empaquetado alternativo: `./build-exe.ps1 -App v2` (o `build-exe.bat` para v1).

### v3 — SaaS (app + backend)
```bash
# 1) backend (H2 en memoria por defecto, puerto 8080):
./gradlew :backend-saas:bootRun
# 2) app:
./gradlew :app-v3-saas:jpackageImage
# exe: app-v3-saas\build\jpackage\mp3ConverterSaaS\mp3ConverterSaaS.exe
# o en desarrollo: ./gradlew :app-v3-saas:run
```
- Por defecto la app apunta a `http://localhost:8080`; para un backend
  desplegado: `$env:SAAS_API_URL = "https://tu-backend.onrender.com"`.
- Con credenciales reales (opcionales) el backend usa Supabase/Postgres:
  ```powershell
  $env:SAAS_DB_URL     = "jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres"
  $env:SAAS_DB_USER    = "postgres.<ref>"
  $env:SAAS_DB_PWD     = "<password>"
  $env:SAAS_JWT_SECRET = "<secreto fijo, >=32 caracteres>"
  ```
  Sin `SAAS_DB_*` → H2 en memoria (dev). Guía de deploy:
  [`docs/DEPLOY.md`](docs/DEPLOY.md).

### Base de datos (v2, opcional)
```powershell
$env:SUPABASE_DB_URL  = "jdbc:postgresql://db.<proyecto>.supabase.co:5432/postgres?sslmode=require"
$env:SUPABASE_DB_USER = "postgres"
$env:SUPABASE_DB_PWD  = "<password del proyecto>"
```
Sin credenciales todo funciona igual (código de activación "efímero").

## Pruebas
```bash
./gradlew test              # unitarios de todos los módulos (rápidos)
./gradlew check             # unitarios + integración (ffmpeg real) + db-integration
./gradlew dbIntegrationTest # SOLO pruebas contra Supabase real (tag db-integration)
```
Las pruebas de BD e integración se **omiten automáticamente** sin las variables
de entorno correspondientes.

## Arquitecturas (resumen)

**v2:**
```
core (Java, sin JavaFX)
├── conversion:  Mp3ConversionService + QualityPolicy (92k CBR / VBR) + watermark
├── license:     LicenseCodec (MPCL), LicenseVerifier, LicenseController,
│                FieldCrypto (PBKDF2 sal=correo, pepper=apellido), ActivationCodeGenerator
├── payment:     PaymentValidator, PaymentGateway + MockPaymentGateway, PaymentFlow
└── db:          DatabaseConfig (env SUPABASE_DB_*), UserRepository, ActivationRepository

app-v2-freemium (JavaFX)
└── FeatureGate (tier desde .lic, hot flip), ControllerV2, PaymentDialogV2 / ActivationDialogV2
```

**v3:**
```
backend-saas (Spring Boot 3.x, schema saas.*)
├── auth:       register/login (BCrypt + JWT 15 min)
├── billing:    checkout MOCK + webhook (PAID → ACTIVE, idempotente)
└── license:    issue (token 7 días; BD guarda SOLO su HMAC) + heartbeat (gracia 7 días)

app-v3-saas (JavaFX, fork de v2)
├── FeatureGate        tier por suscripción + gracia offline, hot flip
├── SaasApiClient      cliente HTTP puro (login/register/checkout/issue/heartbeat)
├── SaasSessionStore   sesión local (token de licencia + correo; NUNCA password ni JWT)
└── SubscriptionDialogV3  cuenta → checkout en navegador → PRO en caliente
```

## Estructura del repositorio
```
mp3Converter/
├── core/                  ← lib compartida (conversión + licencias + pago + DB cifrada)
├── app-v1-desktop/        ← v1.0.0 Desktop (JavaFX + jpackage, congelado)
├── app-v2-freemium/       ← v2.0.0 pay-to-pro (compra → activación → PRO)
├── app-v3-saas/           ← v3.0.0 app SaaS (suscripción + heartbeat)
├── backend-saas/          ← v3.0.0 backend SaaS (Spring Boot: auth, billing, licencias)
├── docs/                  ← PLAN_MAESTRO.md, criptografia.md, DEPLOY.md, saas/
├── parked/                ← código aparcado (no entra en el build)
├── build-exe.bat / build-exe.ps1   ← empaquetado por variante (v1|v2)
├── settings.gradle.kts
└── build.gradle.kts       ← raíz (multi-módulo Gradle)
```

## Documentación adicional
- Plan de acción completo (fases, características, pruebas y riesgos):
  [`docs/PLAN_MAESTRO.md`](docs/PLAN_MAESTRO.md).
- Diseño criptográfico (por qué sal=correo y pepper=apellido, formato MPCL,
  hashes en BD): [`docs/criptografia.md`](docs/criptografia.md).
- Deploy del backend SaaS (Render/Docker):
  [`docs/DEPLOY.md`](docs/DEPLOY.md).
- Arquitectura SaaS: [`docs/saas/ARCHITECTURE.md`](docs/saas/ARCHITECTURE.md).

## Licencia
MIT (ver [`LICENSE`](LICENSE)).
