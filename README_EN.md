# mp3Converter — Multi-project Portfolio

> **Versión en español:** [README.md](README.md)

A single Gradle repository containing 3 Java projects from the same audio
business, each at a different stage of evolution. All of them share the
conversion logic (the `core` module, external ffmpeg via ProcessBuilder).

## ⚠️ Notice: portfolio project

This repository is a **portfolio project for personal practice purposes only**.
It is not production software or a commercial product:

- The payment gateway is **MOCK** (simulated): it always approves and **handles
  no real money**. Card data is never stored — not on disk, not in the
  database, not in logs.
- Activation codes, licenses and subscriptions exist to demonstrate software
  design, not to charge for the product.
- Do not use it to process real payments or as the base of a product without
  adapting it first (real gateway, terms, privacy, etc.).

## What does this project do?

It is a **desktop MP3 converter for Windows (JavaFX)** that accepts
WAV/AIFF/FLAC/M4A/OGG/WMA/.opus, delegates conversion to **ffmpeg**, and
controls the output quality. On top of that shared base, three "product
versions" demonstrate three different business models:

| Module | Project | Version | Model | Status |
|--------|---------|---------|-------|--------|
| `app-v1-desktop` | MP3Converter Desktop | 1.0.0 | Free, no restrictions | Frozen (historical) |
| `app-v2-freemium` | MP3Converter Freemium | 2.0.0 | Freemium **pay-to-pro** (local license) | Complete |
| `app-v3-saas` + `backend-saas` | MP3Converter SaaS | 3.0.0 | **Subscription** (Spring Boot backend) | Complete |

## Purpose and changes between versions

### v1.0.0 — Desktop (frozen)
- **Purpose:** the historical starting point: a complete, unrestricted
  converter (VBR `-q:a 2`, no watermark).
- **What it demonstrates:** real ffmpeg conversion, JavaFX UI, self-contained
  `jpackage` packaging (no Java required at runtime).
- **Status:** receives no changes; it stands as the "before" reference.

### v2.0.0 — Freemium pay-to-pro
- **Changes vs. v1:** introduces the freemium model on the same UI:
  - **Free tier:** fixed **92 kbps CBR** quality (never VBR), an audible
    "mp3Converter" watermark at the start and end of every MP3, and `.opus`
    files are listed but require Pro.
  - **Pro tier:** full VBR, no watermark, `.opus` enabled.
  - Full **purchase → activation** flow: payment form with exact validations
    (Luhn, CVV, unexpired date, name/address/phone rules), mock gateway,
    **activation code** and the `mp3converter.lic` license file (encrypted
    MPCL binary: AES-256-GCM + HMAC-SHA256).
  - **Hot flip to PRO** (no restart) and **per-folder licensing**
    (multi-instance: each copy of the exe has its own state).
  - Optional **Supabase** persistence with encryption: `email_hash` (HMAC),
    AES-256-GCM fields; **never plaintext** in the database.

### v3.0.0 — Subscription SaaS
- **Changes vs. v2:** the local `.lic` license disappears; the Pro tier is now
  defined by an **active subscription on a real backend**:
  - **Spring Boot 3.x backend** (`backend-saas`): register/login with **JWT**,
    subscriptions, a **mock** checkout page served by the backend itself (its
    "Pay" button fires the webhook, mimicking a real gateway's hosted
    checkout), license token issuance and **heartbeat**.
  - In the database, the license token is stored **only as an HMAC**;
    passwords with BCrypt; the email only as a hash (the original is AES
    encrypted).
  - App (a fork of v2): subscription dialog (create account → checkout in the
    browser → hot flip to PRO), a **heartbeat daemon** that re-checks every
    ~24 h, and a **7-day offline grace** window when the backend is
    unreachable.
  - The card **never goes through the app**: checkout happens in the browser.
  - Verified against **real Supabase** (the `db-integration` test) and
    validated end-to-end in a Docker container.

## How to build and run

**Requirements:** Java 21 (JDK: <https://adoptium.net/>) to build; **ffmpeg**
on the PATH to convert (`winget install ffmpeg`). The jpackage `.exe` binaries
are self-contained: **they do not require Java installed**.

### v1 — Desktop
```bash
./gradlew :app-v1-desktop:jpackageImage
# exe: app-v1-desktop\build\jpackage\mp3Converter\mp3Converter.exe
# or during development: ./gradlew :app-v1-desktop:run
```

### v2 — Freemium
```bash
./gradlew :app-v2-freemium:jpackageImage
# exe: app-v2-freemium\build\jpackage\mp3ConverterFree\mp3ConverterFree.exe
# or during development: ./gradlew :app-v2-freemium:run
```
- Without a license it starts in **Free**. "Comprar versión Pro" → form →
  mock gateway → **copy code** → "Activar contrato Pro" → PRO instantly.
- `mp3converter.lic` lives next to the exe; **deleting it = back to Free**.
- Alternative packaging: `./build-exe.ps1 -App v2` (or `build-exe.bat` for v1).

### v3 — SaaS (app + backend)
```bash
# 1) backend (in-memory H2 by default, port 8080):
./gradlew :backend-saas:bootRun
# 2) app:
./gradlew :app-v3-saas:jpackageImage
# exe: app-v3-saas\build\jpackage\mp3ConverterSaaS\mp3ConverterSaaS.exe
# or during development: ./gradlew :app-v3-saas:run
```
- By default the app targets `http://localhost:8080`; for a deployed backend:
  `$env:SAAS_API_URL = "https://your-backend.onrender.com"`.
- With real credentials (optional) the backend uses Supabase/Postgres:
  ```powershell
  $env:SAAS_DB_URL     = "jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres"
  $env:SAAS_DB_USER    = "postgres.<ref>"
  $env:SAAS_DB_PWD     = "<password>"
  $env:SAAS_JWT_SECRET = "<fixed secret, >=32 characters>"
  ```
  Without `SAAS_DB_*` → in-memory H2 (dev). Deploy guide:
  [`docs/DEPLOY.md`](docs/DEPLOY.md).

### Database (v2, optional)
```powershell
$env:SUPABASE_DB_URL  = "jdbc:postgresql://db.<project>.supabase.co:5432/postgres?sslmode=require"
$env:SUPABASE_DB_USER = "postgres"
$env:SUPABASE_DB_PWD  = "<project password>"
```
Without credentials everything still works (activation code becomes
"ephemeral").

## Tests
```bash
./gradlew test              # unit tests of every module (fast)
./gradlew check             # unit + integration (real ffmpeg) + db-integration
./gradlew dbIntegrationTest # ONLY tests against real Supabase (tag db-integration)
```
Database and integration tests are **automatically skipped** when the
corresponding environment variables are absent.

## Architecture (summary)

**v2:**
```
core (Java, no JavaFX)
├── conversion:  Mp3ConversionService + QualityPolicy (92k CBR / VBR) + watermark
├── license:     LicenseCodec (MPCL), LicenseVerifier, LicenseController,
│                FieldCrypto (PBKDF2 salt=email, pepper=lastName), ActivationCodeGenerator
├── payment:     PaymentValidator, PaymentGateway + MockPaymentGateway, PaymentFlow
└── db:          DatabaseConfig (env SUPABASE_DB_*), UserRepository, ActivationRepository

app-v2-freemium (JavaFX)
└── FeatureGate (tier from .lic, hot flip), ControllerV2, PaymentDialogV2 / ActivationDialogV2
```

**v3:**
```
backend-saas (Spring Boot 3.x, schema saas.*)
├── auth:       register/login (BCrypt + JWT 15 min)
├── billing:    MOCK checkout + webhook (PAID → ACTIVE, idempotent)
└── license:    issue (7-day token; DB stores ONLY its HMAC) + heartbeat (7-day grace)

app-v3-saas (JavaFX, fork of v2)
├── FeatureGate        subscription-based tier + offline grace, hot flip
├── SaasApiClient      plain HTTP client (login/register/checkout/issue/heartbeat)
├── SaasSessionStore   local session (license token + email; NEVER password or JWT)
└── SubscriptionDialogV3  account → browser checkout → hot flip to PRO
```

## Repository layout
```
mp3Converter/
├── core/                  ← shared library (conversion + licensing + payment + encrypted DB)
├── app-v1-desktop/        ← v1.0.0 Desktop (JavaFX + jpackage, frozen)
├── app-v2-freemium/       ← v2.0.0 pay-to-pro (purchase → activation → PRO)
├── app-v3-saas/           ← v3.0.0 SaaS app (subscription + heartbeat)
├── backend-saas/          ← v3.0.0 SaaS backend (Spring Boot: auth, billing, licenses)
├── docs/                  ← PLAN_MAESTRO.md, criptografia.md, DEPLOY.md, saas/
├── parked/                ← parked code (not part of the build)
├── build-exe.bat / build-exe.ps1   ← packaging per variant (v1|v2)
├── settings.gradle.kts
└── build.gradle.kts       ← root (multi-module Gradle)
```

## Further documentation
- Full action plan (phases, features, tests and risks) — in Spanish:
  [`docs/PLAN_MAESTRO.md`](docs/PLAN_MAESTRO.md).
- Cryptographic design (why salt=email and pepper=lastName, the MPCL format,
  DB hashes) — in Spanish: [`docs/criptografia.md`](docs/criptografia.md).
- SaaS backend deploy guide (Render/Docker):
  [`docs/DEPLOY.md`](docs/DEPLOY.md).
- SaaS architecture — in Spanish:
  [`docs/saas/ARCHITECTURE.md`](docs/saas/ARCHITECTURE.md).

## License
MIT (see [`LICENSE`](LICENSE)).
