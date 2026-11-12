 # Plan de Acción — Portfolio mp3Converter (3 proyectos en 1 repositorio)

> **Estado:** Plan aprobado para ejecución.
> **Meta:** Convertir el repo actual en un portfolio con 3 proyectos:
> 1. **MP3Converter Desktop v1.0.0** (estado actual, intacto como pieza histórica)
> 2. **MP3Converter Freemium v2.0.0** (versión gratuita con límites + activación "pro" por clave)
> 3. **MP3Converter SaaS v3.0.0** (borrador arquitectónico: suscripción mensual/anual)

---

## 1. Características del proyecto

### 1.1 Diagnóstico del estado actual (v1.0.0)
- **Stack:** Java 21 + JavaFX 21.0.6 (FXML), Gradle 9.8.2 (Kotlin DSL), JPMS (`module-info.java`).
- **Plugins build:** `org.javamodularity.moduleplugin` 2.1.0, `org.openjfx.javafxplugin` 0.1.0, `org.beryx.jlink` 4.2.0 (jlink + jpackage → `.exe` autocontenido, icono `nuevo-logo.ico`, scripts `build-exe.bat`/`build-exe.ps1`).
- **Conversión:** binario externo **ffmpeg** vía `ProcessBuilder`; calidad VBR fija (`-q:a 2`, libmp3lame), **sin selector de kbps**. Timeout 15 min/archivo, cancelación soportada, resumen `ConversionSummary`.
- **Formatos entrada:** wav, aif, aiff, flac, m4a, ogg, wma. **".opus" NO está soportado** (se ignora). `.mp3` se rechaza como origen.
- **Peristencia:** ninguna. **Tests:** unit (`Mp3ConversionServiceUnitTest`) + integración (`Mp3ConversionServiceIntegrationTest`, tag `integration`, skip si no hay ffmpeg).
- **Faltantes:** README, LICENSE, limpieza de artefactos committeados en `build\`.

### 1.2 Decisiones arquitectónicas acordadas
| # | Decisión | Elección |
|---|----------|----------|
| D0 | Flujo de Git | Todo el trabajo en la rama **`feature-pay-to-pro`** (creada desde `master` version 1.0.0 ya mergeado en remoto); PR a `master` por fase |
| D1 | Estructura del repo | **Gradle multi-módulo**: `core` compartido + 3 apps independientes |
| D2 | Base de datos | **Supabase** (Postgres serverless, gratis, JDBC `org.postgresql`, sin tarjeta) |
| D3 | Modelo de activación | **Un solo ejecutable** con modo pro activable vía archivo binario de licencia junto al `.exe` |
| D4 | Proyecto 3 | Plan completo borrador (arquitectura incluida), implementación posterior |
| D5 | Pasarela de pago | Interfaz `PaymentGateway` + `MockPaymentGateway` (aprueba siempre) |

### 1.3 Estructura propuesta del repositorio
```
mp3Converter/
├── settings.gradle.kts          ← incluye los subproyectos
├── build.gradle.kts             ← raíz: config común de subproyectos
├── core/                        ← lib compartida (Java 21, sin UI de app)
│   └── com.casl.mp3converter.core
│       ├── ffmpeg/  (FfmpegLocator, Mp3ConversionService, ConversionSummary, ConversionServiceException)
│       ├── audio/   (RateLimiter? no — QualityPolicy, WatermarkInjector)
│       ├── license/ (LicenseFileCodec, LicenseVerifier, ActivationCodeGenerator, CryptoUtil AES-256-GCM)
│       └── db/      (DatabaseConnection, UserRepository, ActivationRepository, entidades)
├── app-v1-desktop/              ← Proyecto 1 "MP3Converter" v1.0.0 (jpackage → exe)
│   └── com.casl.mp3converter.v1
├── app-v2-freemium/             ← Proyecto 2 v2.0.0 (jpackage → exe; mismo componente visual + pantallas nuevas)
│   └── com.casl.mp3converter.v2
│       ├── freemium/  (FeatureGate, FreeTierPolicy)
│       ├── payment/   (PaymentFormController, PaymentValidator, PaymentGateway, MockPaymentGateway)
│       ├── license/   (LicenseController, LicenseWriter)
│       └── db/        (bootstrap de conexión, migraciones SQL)
├── app-v3-saas/                 ← Proyecto 3 v3.0.0 (borrador, stubs)
├── scripts/ (build-exe.bat, build-exe.ps1 adaptados por-variante)
├── docs/ (README.md raíz + README por proyecto)
└── gradle/wrapper/
```
Reglas clave:
- `core` **sin JavaFX** desacopla la lógica y la hace testeable; cada `app-*` es un proyecto jpackage con su propio `module-info`, nombre de exe y versión.
- Proyecto 1 debe permanecer **byte-a-byte equivalente en comportamiento** al v1.0.0 actual.
- Servicios de freemium invertidos: la app depende de interfaces (`QualityPolicy`, `PaymentGateway`), **nunca** llama ffmpeg con parámetros prohibidos.

---

## 2. Plan de ejecución

### Fase 0 — Reestructuración multi-módulo (sin cambios de comportamiento)
0. **Git — precondición obligatoria:**
   - El usuario primero hará push/PR/merge a `master` del estado actual 1.0.0 pendiente (~10–15 min por su parte). Este plan **no se ejecuta hasta que `master` esté en verde en remoto**.
   - A partir de ahí, **TODOS los cambios de este plan se hacen en la rama `feature-pay-to-pro`**: `git checkout -b feature-pay-to-pro` (desde `master` actualizado), con PR hacia `master` por fase (o al menos un PR final por proyecto) para mantener el historial revisable.
   - Nada se commitea directo a `master` dentro de este plan; v1 se congela al estado del merge inicial.
1. Crear `settings.gradle.kts` con `include("core", "app-v1-desktop")` (los demás se van agregando por fase).
2. Mover la lógica de conversión a `core` (interfaces y clases sin UI) y dejar en `app-v1-desktop` solo: `HelloApplication`, `HelloController`, FXML, icono, launcher, module-info.
3. Cada app con su propia config `jlink/jpackage` (nombre de exe, versión, vendor). Actualizar `build-exe.ps1` para aceptar variante (`-App v1|v2`).
4. Verificación: `gradlew check` verde en raíz (running unit + integration); `jpackageImage` de v1 produce el mismo comportamiento real.
5. Limpieza: retirar binarios committeados de `build\`, añadir README raíz (mapa del portfolio) y LICENSE.
- **Criterio de aceptación:** v1 comparte `core`, todos los tests pasan, se generan 2 artefactos por propuesta (v1 y futuro v2) sin regresión.

### Fase 1 — `core`: calidad parametrizable + soporte `.opus` (base para v2)
1. Sustituir `-q:a 2` por una `QualityPolicy`:
   - `FREE: CBR 92 kbps` → `ffmpeg -b:a 92k` (fijo, no VBR).
   - `PRO: todas` (320 kbps y `-q:a 2`), selector en UI solamente en modo pro.
2. Añadir `"opus"` a `SUPPORTED_EXTENSIONS` y `SUPPORTED_EXTENSIONS_TEXT` (ffmpeg lo decodifica; la restricción de conversión es política del tier, no técnica).
3. Tests unitarios de `QualityPolicy` + reproducción de casos previos.
- **Criterio:** core expone la capacidad completa y el gate por política; v1 no cambia.

### Fase 2 — app-v2-freemium: restricciones del tier gratuito
1. Copiar layout de v1; **nuevo package `com.casl.mp3converter.v2`**, versión `2.0.0`.
2. `FeatureGate` decide en runtime según licencia (ver Fase 4):
   - **2.1 Calidad** → forzar `-b:a 92k` en free; si no hay licencia, ocultar/deshabilitar selector de calidad.
   - **2.2 Watermark ✅ DECIDIDO: AUDIBLE** → insertar, al inicio y al final de cada MP3, la leyenda **"mp3Converter"** como **audio audible de 1–2 s** con voz TTS ya generada, protegida y fusionada al MP3 durante la funcion de conversion (con fade in/out para suavizar el corte).
   - **2.3 .opus** → se listan en la tabla de resultados pero se marcan como "requiere Pro"; ffmpeg nunca se invoca para ellos; mensaje/emergente con CTA "Comprar versión Pro".
   - **2.4 Formato** (asumido copia de v1): si hay activación pro, `QualityPolicy.PRO` + sin watermark + opus habilitado.
3. UI: diálogo "Versión gratuita" con el resumen de límites.
- **Criterio:** sin licencia, ffmpeg solo acepta `-b:a 92k`, el watermark se aplica, .opus bloqueado con mensaje claro.

### Fase 3 — Licenciamiento 2.4 (archivo binario junto al .exe)
1. **Codec + verificador:** `LicenseFileCodec` (formato binario firmado, no transparente) + **`LicenseVerifier`**.
2. **Ubicación:** `jpackage` define la propiedad `jpackage.app-path` → resolver directorio del `.exe` en tiempo real y abrir/crear `mp3converter.lic` al lado.
3. **Formato binario** (con cabecera clara):
   - Magic number `MPCL` + versión de formato.
   - Payload: `id, email_hash, activation_code, issued_at, tier` + checksum.
   - **Protección = AES-256-GCM (confidencialidad) + HMAC-SHA256(payload, clave secreta embebida) (integridad).**
4. **Carga**: al arrancar, `LicenseVerifier.load()` → tier=PRO; archivo expirado/manipulado/corrupto → tier=FREE y el usuario puede re-activar.
5. **Bonus** (fuera de scope por ahora pero anotado): firma asimétrica (Ed25519) para permitir redistribuir la clave pública sin exponer el secreto de firma.
- **Criterio:** sin archivo → free; con archivo bien firmado → pro; archivo corrupto/manipulado → free, con log auditado.

### Fase 4 — 2.5/2.5.2/2.7 Base de datos (Supabase) y persistencia segura
1. Proyecto Supabase (gratuito, región Mexico/us-east recomendado) → obtener `Project URL`, `service key` y `connection string` Postgres.
2. **Acceso:** conector JDBC `org.postgresql` en `core/db` + pooling simple (HikariCP) + migraciones versionadas SQL (tablas `users` y `activations`):
   - `users (id, email_hash, first_name_enc, last_name_enc, phone_enc, tier, created_at, updated_at)` **sin tarjetas**.
   - `activations (id, user_id, code_hash, salt, issued_at, status, deactivated_at)`.
3. **Criptografía aplicada (2.5.2):**
   - Cifrado AES-256-GCM por campo; derivación de clave `PBKDF2WithHmacSHA256` con **sal = correo electrónico del usuario** y **pepper = apellido del usuario** (+ valor aleatorio persistido por usuario en `salt`).
   - Código de activación: se genera aleatorio (SecureRandom), se guarda **hasheado (SHA-256)** para lookup y **cifrado** para recuperación.
   - `CryptoUtil` en `core/license` unifica AES/HMAC/PBKDF2 y encoding. Documentación en `docs/criptografia.md` (justificación de sal/pepper + decisión de no almacenar datos bancarios).
4. **Connection config**: no se hardcodea: config cifrada local o variables de entorno (`SUPABASE_DB_URL`, `SUPABASE_DB_USER`, `SUPABASE_DB_PWD`), documentado en README.
5. Tests unitarios de repositorios mockeando el JDBC + tests de integración de BD **taggeados `db-integration`** (se ejecutan con credenciales Supabase presentes, skip automático si no).
- **Criterio:** "sin subtítulos en claro" en DB; el flujo de almacenamiento funciona; clave única si se des-duplica contra otra cuenta.

### Fase 5 — 2.5/2.5.1/2.6 Formulario de pago + pasarela mock
1. **2.5 Formulario modal JavaFX** con validación exacta:
   | Campo | Regla |
   |-------|-------|
   | Nombre + Apellido | solo letras (incluye acentos/n), mínimo 2 caracteres, sin dígitos/símbolos |
   | Correo | regex RFC-5322 diffácil, normalizado |
   | Teléfono | prefijo de **10 dígitos** + dropdown con claves de país por default **+52 (México)** |
   | Dirección | máx. 70 caracteres y **debe contener un `#`** |
   | Tarjeta | **16 dígitos** (formato dinámico con espacios visuales) + validación Luhn |
   | Vencimiento | formato `mm/AAAA` válido, no expirado |
   | CVV | 3 dígitos |
   Mensaje de error inline específico por campo; botón "Pagar" habilitado solo cuando todo es válido.
2. **2.5.1 Abstracción pasarela:** interfaz `PaymentGateway { ChargeResult charge(PaymentRequest req); }`; `MockPaymentGateway` **aprueba siempre** (con log y latencia simulada). Implementación real futura (Stripe/MercadoPago) plug-in a la misma interfaz. **Los datos bancarios se usan solo para la llamada al gateway: nunca se persisten, ni even en logs (solo máscara `**** 1234`).**
3. **2.5.2 Persistencia:** al aprobar el pago mock → cifrar con AES-256-GCM (sal=correo, pepper=apellido) nombre, apellido, email, teléfono, código de activación → `UserRepository`/`ActivationRepository`. Verificación anti-duplicado por `email_hash`.
4. **2.6 Cuadro emergente de activación** con la leyenda exacta "esta es tu clave de activación, puedes guardarla para usarla nuevamente en caso que quieras activar de nuevo tu mp3converter" + botón **copiar al portapapeles** + botón **activar contrato "Pro"** (llama a `LicenseController.activate(code)`).
5. **2.6 Escritura de licencia local:** `LicenseController.activate(code)` → escribir el `mp3converter.lic` cifrado/firmado junto al `.exe` (formato de la Fase 3) y voltear `FeatureGate` a modo pro en caliente (sin reiniciar).
- **Criterio:** flujo completo: form → mock APRUEBA → código generado y cifrado en DB → diálogo con leyenda + copiar → activación → pro habilitado.

### Fase 6 — app-v2-freemium: integración E2E + empaquetado 2.0.0
1. Integrar todo: arranque v2 → `LicenseVerifier` → pro/free; reglas del tier aplicadas en caliente.
2. Test de fila E2E en JVM local: conversión libre de flujo de compra → activación → desbloqueo; y regreso a free cuando se elimina la licencia.
3. `gradlew :app-v2-freemium:jpackageImage` — `.exe` de v2.
4. `build-exe.ps1 -App v2`.
5. README por proyecto con arquitectura, configuración (SUPABASE_*), y guía de activación.
- **Criterio:** `.exe` de v2 corre en máquina sin Java y sin BD local; se activa pro desde 0; dos carpetas de exe pueden tener licencias distintas (multi-instancia).

### Fase 7 — Proyecto 3 (3.0) Saas: borrador arquitectónico (sin implementar)
Propuesta **"app-v3-saas"**, basada en v2:
1. **Modelo de negocio:** licencia **por suscripción** (mensual/anual) validada en línea; con base en Supabase.
2. **Backend ligero** (nuevo módulo `backend-saas`, Java 21 **Spring Boot 3.x**):
   - `POST /api/auth/register`, `POST /api/auth/login` (JWT), `GET/POST /api/subscriptions`, `POST /api/payments/checkout` (pasa a Stripe/MercadoPago gateway real),
   - `POST /api/license/issue` (emite token firmado valid offline 7 días) y `POST /api/license/heartbeat` (verificación periódica desde la app; si la suscripción expira, `FeatureGate` vuelve a FREE tras la ventana de gracia).
   - Tablas nuevas: `plans`, `subscriptions (period, next_billing, status)`, `payments`, `license_tokens`.
3. **Frontend app:** el mismo `app-v2-freemium` con `FeatureGate` que **requiere suscripción activa** y **tolerancia offline** (7 días de gracia por heartbeat vencido).
4. **Decisión de infra:** Supabase solo Postgres (ya la tenemos), backend Spring Boot desplegado en Render/Railway/Fly.io (free tier) — decidir cuando se implemente.
5. Documentación `docs/saas/ARCHITECTURE.md` + diagrama de secuencia del ciclo de vida de suscripción.
- **Criterio:** documento de arquitectura + módulo `backend-saas` vacío (stubs mezclados en el build) y app-v3 lista para empezar cuando se decida.

### Fase 8 — backend-saas real (post-plan, decidida con el usuario)
Implementación del borrador de la Fase 7, sub-fases con commit propio:
1. **8.1** Base Spring Boot real: BD portable H2 dev / Postgres Supabase por env (`SAAS_DB_*`), schema dedicado `saas.*` (5 tablas), reuso de `SchemaInitializer` de core, health-check, smoke test, Dockerfile.
2. **8.2** Auth: `register` (BCrypt + dedupe `email_hash`, 409) y `login` (JWT 15 min, 401); secreto por env `SAAS_JWT_SECRET` (efímero en dev con aviso).
3. **8.3** Facturación: seed de plans, `PaymentGatewaySaaS` + MockCheckoutGateway, checkout PENDING → webhook PAID → suscripción ACTIVE con `next_billing` (renueva, no duplica), cancel en caliente, filtro JWT en endpoints de negocio.
4. **8.4** Licencia: `issue` (exige suscripción ACTIVE; token 24B, BD guarda solo su HMAC con `FieldCrypto.codeHash`) y `heartbeat` (autenticado con el token; renueva la gracia de 7 días; INACTIVE al cancelar; EXPIRED al vencer).
5. **8.5** E2E del ciclo completo de suscripción (equivalente del test de Fase 6).
6. **8.6** Documentación (este plan + README + estado en ARCHITECTURE.md).
- **Criterio:** suite de `backend-saas` verde (contrato + E2E) y `gradlew check` completo en verde.

---

## 3. Estrategia de pruebas — el modelo de negocio demostrado con tests

> **Objetivo:** cada regla del modelo de negocio tiene al menos un test que la demuestra (y uno negativo que confirma que se aplica/rechaza como debe).
> Se mantiene la separación del proyecto actual por tags JUnit (`integration`, y nuevo `db-integration`): `gradlew test` corre lo rápido; las suites con dependencias externas (ffmpeg real, Supabase real) hacen skip automático si no hay credenciales/binarios.

### 3.1 Mapa de pruebas por regla de negocio
| Regla (req.) | Tipo | Clase de test | Escenario clave |
|--------------|------|---------------|-----------------|
| 2.1 Calidad free = 92 kbps | Unit | `QualityPolicyTest` | `FREE.resolveFfmpegArgs()` **produce `-b:a 92k`** y **jamás** `-q:a 2` ni bitrate >92; si el free intenta otra calidad lanza `ProFeatureDenied`. |
| 2.1 Pro desbloquea calidades | Unit | `QualityPolicyTest` | `PRO` acepta 128/192/320 kbps y VBR `-q:a 2`. |
| 2.2 Watermark en free | Unit | `WatermarkInjectorTest` | FREE → el plan de ffmpeg incluye el insert del asset al inicio **y** al final; PRO → ** nunca incluye watermark**. |
| 2.2 Watermark audible real | Integration | `WatermarkConversionIntegrationTest` (tag `integration`) | WAV sintético (como los tests actuales) → convertir en free → el MP3 resultante incorpora la pista del watermark en cabecera y cola (verificación de duración ≥ fuente + 2 inserts); la misma conversión en pro → duración ≈ fuente. |
| 2.3 .opus bloqueado en free | Unit | `FeatureGateOpusTest` | FREE → convertir `.opus` lanza excepción con mensaje "requiere Pro" **sin invocar ffmpeg** (verificado con spy/stub), y el archivo se reporta como omitido-en-lista. |
| 2.3 .opus OK en pro | Unit + Integration | `FeatureGateOpusTest` + `OpusConversionIntegrationTest` | PRO → sí convierte (test con archivo .opus sintético). |
| 2.4 Licencia binaria | Unit | `LicenseFileCodecTest`, `LicenseVerifierTest` | round-trip escribir→leer; payload alterado 1 byte → **inválida por HMAC**; archivo truncado o renombrado ajeno → inválida; sin archivo → FREE. |
| 2.4 Licencia junto al exe | Integration | `LicenseIntegrationTest` (tag `integration`) | escribir `.lic` en carpeta temporal → `LicenseVerifier` carga → PRO; borrar el archivo → al reiniciar vuelve a FREE. |
| 2.5 Validación formulario | Unit | `PaymentValidatorTest` (parametrizado) | caso feliz por campo + rechazos: 15/17 dígitos de tarjeta, CVV de 2 o 4 dígitos, `13/2099` y mes pasado, nombre con dígito/símbolo o de 1 letra (inválidos), nombre con acentos y `ñ` (**válido**), dirección de 71 chars o sin `#`, teléfono de 9 u 11 dígitos, emails sin `@`/dominio. |
| 2.5.1 Contrato de pasarela | Unit | `MockPaymentGatewayTest` | el mock aprueba siempre (`ChargeResult.approved()`), dispara la generación del código y **los logs nunca contienen el número completo de tarjeta (solo máscara `**** 1234`).** |
| 2.5.2 Cifrado con sal/pepper | Unit | `CryptoUtilTest` | round-trip encrypt/decrypt; **mismo contenido con distintos correos (sal) produce ciphertexts distintos**; con pepper (apellido) incorrecto no se descifra. |
| 2.5.2 Persistencia | Unit + Integration | `ActivationRepositoryTest` (JDBC mock) + `SupabaseActivationIntegrationTest` (tag `db-integration`) | guardar→recuperar→desencriptar correcto y **ningún campo legible en claro en la fila de DB**; segunda activación del mismo correo no duplica `users`. |
| 2.6 Código de activación | Unit | `ActivationCodeGeneratorTest` | unicidad, formato estable, código correcto aceptado / falso código rechazado al activar. |
| 2.6 Flujo compra→pro completo | Integration | `PurchaseToProFlowTest` (tag `integration`) | formulario → mock aprueba → código generado → `.lic` escrito → `FeatureGate` pasa a PRO **en caliente**: la siguiente conversión ya no tiene watermark, sí 320 kbps y .opus se convierte; borrar `.lic` y re-arrancar → regreso a FREE. |

### 3.2 Convención de suites
```
gradlew test                 → unit tests de todos los módulos (rápido, sin ffmpeg/DB)
gradlew integrationTest      → ffmpeg real + licencias (tag "integration"; skip si no hay ffmpeg)
gradlew dbIntegrationTest    → Supabase real (tag "db-integration"; skip si faltan SUPABASE_* env vars) ← se agrega en Fase 4
gradlew check                → test + integrationTest (como en v1) + dbIntegrationTest cuando aplique
```
- La convención actual de compilar tests en classpath (no module-path) se mantiene.
- **Regla de avance:** cada fase debe quedar en verde antes de empezar la siguiente; si un test se rompe, se corrige antes de continuar.

### 3.3 Criterio global de éxito del proyecto 2
> En una máquina limpia: `gradlew check` verde → `gradlew :app-v2-freemium:jpackageImage` → correr el `.exe` → demostrar el ciclo completo de punta a punta: **gratis (92 kbps + watermark + .opus bloqueado) → compra mock → activación → pro completo (320 kbps + sin watermark + .opus OK) → desactivar/borrar licencia → regreso a gratis**.

---

## 4. Riesgos y mitigaciones
| Riesgo | Mitigación |
|--------|------------|
| Licencia crackeable (clave en binario) | Es una app de portfolio: HMAC + AES-GCM; en docs se aclara que la protección pesada requiere backend; en v3 hay heartbeat online. |
| Supabase gratis suspendido o límites | Config externa (env vars) para cambiar de proveedor JDBC Postgres en 1 línea. |
| Watermark audible (legibilidad) | ~~En revisión~~ → **Decision tomada (audible)** con niveles de volumen moderados y fades para no molestar al usuario free. |
| Multi-proyecto rompe v1 | Fase 0 independiente y verificada antes de agregar v2. |
| Fuente de datos de prefijos de teléfonos | Lista curada en core (`CountryCodes`), texto por defecto +52. |

---

## 5. Registro de decisiones pendientes (a justificar durante ejecución)
1. ~~Voz del watermark~~ → **RESUELTO**: opción audible confirmada (1–2 s TTS). Asset del sonido se genera en la Fase 2 y se versiona en `core/src/main/resources/audio/watermark-mp3converter.wav`.
2. **Datos de tarjeta en el log de la llamadas a `PaymentGateway`**: nunca persistir, sí loggear con `***mask***` — decisión por defouto a documentar.
3. **Región de Supabase** y wildcard de correo: pantalla de aceptación vista.
4. **Migraciones SQL** en Supabase ejecutadas manualmente vía SQL editor o aterrizar en Flyway en `core/db` — decidir en Fase 4.

---

## 6. Anexos — Referencia de comandos
```bash
# Build completo
./gradlew build               # unit + integration en raíz (pasa 3 proyectos cuando existan)

# Empaquetar v1
./gradlew :app-v1-desktop:jpackageImage

# Empaquetar v2 / futura
./gradlew :app-v2-freemium:jpackageImage

# Script existente
build-exe.bat                 # adaptado: -App v1|v2
```
