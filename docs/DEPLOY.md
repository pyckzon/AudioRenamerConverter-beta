# Deploy del backend SaaS (`backend-saas`) — Fase 10

> **Decisión del usuario (Fase 10):** la pasarela queda como **MOCK
> definitiva** para este portfolio — no se integran Stripe/MercadoPago.
> El valor del proyecto está en el **registro completo en la base de datos**
> (pago PAID, suscripción ACTIVE/CANCELED, token de licencia sólo como HMAC),
> ya verificado contra Supabase real con `SupabaseSaasLifecycleIntegrationTest`.

## Qué verifica la prueba de registro en BD (10.1)

`backend-saas:dbIntegrationTest` (tag `db-integration`) corre contra
Supabase REAL cuando existen `SAAS_DB_URL/USER/PWD` (skip automático sin
ellas, misma regla que core). Asserts por usuario (nunca `COUNT(*)` global):

1. El pago queda **PAID** en `saas.payments` (gateway MOCK, monto/moneda del plan).
2. La suscripción queda **ACTIVE** y **CANCELED** (con `canceled_at`) tras la baja.
3. El token de licencia se guarda **SÓLO como HMAC de 64 hex** (jamás el token crudo).
4. La password es **BCrypt** (nunca plaintext) y no hay columnas de tarjeta.

Validación de deploy hecha localmente (10.2): `bootJar` + `docker build` +
`docker run` contra Supabase real, ciclo HTTP completo register → login →
checkout → webhook → issue → heartbeat ACTIVE.

## Deploy en Render (guía paso a paso)

El backend es un contenedor Docker autocontenido (Java 21, Spring Boot,
schema `saas.*` auto-migrado al arranque).

1. **Empuja el repo a GitHub** (ya está; Render lo clona).
2. En Render: **New → Web Service** → apunta al repo `pyckz/AudioRenamerConverter-beta`.
3. **Runtime: Docker**. Docker context dir: la raíz del repo; Dockerfile path:
   `backend-saas/Dockerfile`. (El Dockerfile hace `COPY
   backend-saas/build/libs/backend-saas-3.0-SNAPSHOT.jar`, así que Render
   ejecuta el Gradle build automáticamente — o usa el comando de build
   `./gradlew :backend-saas:bootJar` como Build Command si prefieres).
4. **Variables de entorno** (nunca en el repo):
   | Variable | Valor |
   |----------|-------|
   | `SAAS_DB_URL` | `jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres` |
   | `SAAS_DB_USER` | `postgres.<ref>` |
   | `SAAS_DB_PWD` | la password de la BD (guárdala igual que las demás, fuera del repo) |
   | `SAAS_JWT_SECRET` | secreto ≥ 32 caracteres, FIJO (si cambia, se invalidan los JWT vivos) |
   | `SAAS_PORT` | `8080` (el que Render espera en el puerto web) |
   | `SAAS_PUBLIC_URL` | `https://<tu-servicio>.onrender.com` — la URL de los checkouts mock |
   > `SAAS_PUBLIC_URL` alimenta la prop `saas.public.url` del gateway mock
   > por el relaxed binding de Spring Boot (guiones bajos → puntos).
5. **Free tier**: Render duerme el servicio tras inactividad — el primer
   heartbeat de la app puede tardar ~50 s en despertarlo (la gracia offline
   de 7 días cubre ese arranque frío).
6. Health check path: `/api/health`.

### Conectar la app v3 al backend desplegado

En la máquina (o empaquetado en el `.exe` con jlink env):

```powershell
$env:SAAS_API_URL = "https://<tu-servicio>.onrender.com"
```

La app usa la prop en este orden: `mp3converter.saas.api` → env
`SAAS_API_URL` → `http://localhost:8080`.

## Alternativa local con Docker (lo que ya se probó)

```bash
./gradlew :backend-saas:bootJar
docker build -f backend-saas/Dockerfile -t mp3converter-saas .
docker run -p 8080:8080 \
  -e SAAS_DB_URL="jdbc:postgresql://..." \
  -e SAAS_DB_USER="postgres.<ref>" \
  -e SAAS_DB_PWD="..." \
  -e SAAS_JWT_SECRET="<secreto >=32 caracteres>" \
  mp3converter-saas
```

Ciclo de prueba rápido (PowerShell):

```powershell
Invoke-RestMethod http://localhost:8080/api/health
# register → login → checkout → webhook PAID → issue → heartbeat ACTIVE
```
