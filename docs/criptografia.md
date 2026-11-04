# Criptografía y seguridad de datos — MP3Converter v2 (fases 3-5)

Decisiones de diseño de la capa de seguridad, tal como las pide el plan (Fase 4, punto 3:
"Criptografía aplicada (2.5.2)").

## 1. Licencia binaria local (`mp3converter.lic`, requisito 2.4)

- Formato no transparente `MPCL`: `magic(4) | versión(1) | longitud(4) | body(Nonce12||AES-256-GCM||Tag16) | HMAC(32)`.
- **AES-256-GCM** para confidencialidad, **HMAC-SHA256** para integridad.
- Propiedad de seguridad: cualquier byte alterado (incluso del propio HMAC) invalida la licencia.
- Las llaves viven embebidas en el binario (`LicenseSecrets`). Límite explícito y documentado:
  quien de-ensambla el ejecutable podría extraerlas. Es el estándar de licenciamiento offline
  de escritorio; la esclavización dura la aporta el backend (SaaS v3) con `heartbeat` online.

## 2. Cifrado por campo en Supabase (requisito 2.5.2)

| Elemento | Valor | Justificación |
|---|---|---|
| Cifrado | **AES-260? AES-256-GCM** por campo | confidencialidad + integridad por campo |
| KDF | **PBKDF2WithHmacSHA256**, 210 000 iteraciones, 256 bits | resistencia fuerza bruta |
| Password del KDF | `correo_normalizado + ":" + apellido` | aterrizado del plan: **sal = correo electronico**, **pepper = apellido** |
| Salt aleatoria | 16 bytes SecureRandom, persistida en `users.salt` | necesaria para re-derivar la MISMA clave del usuario |
| Identificadores | `email_hash` / `code_hash` = **HMAC-SHA256(correo|code, secreto del binario)** | lookup rápido sin material legible; el secreto no sale del binario |

### Reglas verificadas con pruebas (mapa de pruebas del plan)

1. **Round-trip**: el campo original se recupera con la pareja EXACTA `correo + apellido`.
2. **Sal distinta => ciphertext distinto**: el mismo `plaintext` con otro correo produce
   bytes diferentes, y NO se descifra cruzado.
3. **Pepper incorrecto NO descifra**: apellido con/sin tilde ya es otro pepper (caso
   "García" vs "Garcia" falla por diseño; el formulario envía el valor tal cual).
4. **Sin subtítulos en claro en DB**: los tests leen la FILA CRUDA (`SELECT *`) y fallan si
   encuentran el correo, el nombre, el apellido, el teléfono o el código accionable.

## 3. Código de activación (requisito 2.6)

- Generado con `SecureRandom`, formato `MP3C-XXXX-XXXX-XXXX` con alfabeto sin ambigüedades
  (sin `0/O` ni `1/I/L`), ~62 bits de entropía.
- Se muestra en claro UNA sola vez al usuario (diálogo de la fase 5).
- En la base de datos vive **dos veces**: `code_hash` (HMAC, para lookup/validación) y
  `code_enc` (AES-GCM con la clave del usuario, para recuperación por soporte).
- "Clave única": cada nueva emisión desactiva (`DEACTIVATED`) los códigos ACTIVE previos
  de la misma cuenta; un solo código vigente por usuario.

## 4. Datos bancarios — decisión de diseño (2.5.1)

**Los datos de la tarjeta NUNCA tocan la base de datos ni los logs.** La tabla `users`
no tiene columnas de pago por diseño (ver `V1__init.sql`). La tarjeta vive solamente:
como parámetro de la llamada a `PaymentGateway.charge(...)` (fase 5) y en el log del
gateway SOLO con máscara `**** 1234`. Con la pasarela real (Stripe/MercadoPago) la
información del pago la retiene la pasarela, no el comercio.

## 5. Conexión a la base de datos (fase 4, punto 4)

- Credenciales por variables de entorno `SUPABASE_DB_URL`, `SUPABASE_DB_USER`,
  `SUPABASE_DB_PWD` (o system properties `mp3converter.db.*` para tests). Nada hardcoded.
- Sin la configuración, la parte de base de datos queda deshabilitada y la app sigue
  funcional en modo FREE (comportamiento degradado, probado).
- Pooling: **no se incluyó HikariCP**. Una app de escritorio abre 0..1 conexiones en su
  vida útil; la política es "una conexión por operación" y las pruebas usan H2 en memoria
  (mismo SQL, portabilidad real verificada). Si el producto escala, el cambio se hace en
  `ConnectionProvider` sin tocar los repositorios.

## 6. Migraciones

`core/src/main/resources/db/migration/V1__init.sql`, idempotente (`IF NOT EXISTS`), ejecutado
por `SchemaInitializer` al primer uso. Flyway queda estacionado por simplicidad del
portfolio (una sola migración; invariante verificado por pruebas en H2 **y** Supabase real
cuando haya credenciales).
