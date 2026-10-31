---
sessionId: session-261030-182549-1c7g
---

# Plan: Fix Project Warnings

### ✓ Step 1: Remove unused import from HelloController.java
### ✓ Step 2: Clean up unused requires in module-info.java
### ✓ Step 3: Clean up unused modules and dependencies in build.gradle.kts
### ✓ Step 4: Fix broad exception catch in Mp3ConversionService.java
### ✓ Step 5: Verify the project compiles successfully

---

## Warnings Identificados

### Warnings del Proyecto

Después de revisar todos los archivos del proyecto, he identificado los siguientes warnings:

---

#### 1. `HelloController.java` — Import sin usar
- **Línea 17**: `import java.util.List;` — El tipo `List` nunca se usa en este archivo.

#### 2. `module-info.java` — Módulos `requires` sin usar
Los siguientes módulos se declaran pero **nunca se importan ni usan** en ningún archivo del proyecto:
- **Línea 5**: `requires javafx.web;` — No se usa `javafx.web` en ningún archivo.
- **Línea 7**: `requires org.controlsfx.controls;` — No se usa controlsfx.
- **Línea 8**: `requires com.dlsc.formsfx;` — No se usa formsfx.
- **Línea 9**: `requires net.synedra.validatorfx;` — No se usa validatorfx.
- **Línea 10**: `requires org.kordamp.ikonli.javafx;` — No se usa ikonli.
- **Línea 11**: `requires eu.hansolo.tilesfx;` — No se usa tilesfx.

#### 3. `build.gradle.kts` — Módulo javafx.swing sin usar
- **Línea 35**: `"javafx.swing"` en la lista de módulos javafx — No se usa `javafx.swing` en el proyecto ni en `module-info.java`.

#### 4. `build.gradle.kts` — Dependencias sin usar
Las siguientes dependencias se declaran pero no se usan en el código (porque los módulos correspondientes tampoco se usan):
- **Línea 39**: `org.controlsfx:controlsfx:11.2.1`
- **Línea 40-42**: `com.dlsc.formsfx:formsfx-core:11.6.0`
- **Línea 43-45**: `net.synedra:validatorfx:0.6.1`
- **Línea 46**: `org.kordamp.ikonli:ikonli-javafx:12.3.1`
- **Línea 47-49**: `eu.hansolo:tilesfx:21.0.9`

#### 5. `Mp3ConversionService.java` — Catch de `Exception` demasiado amplio
- **Línea 135**: `catch (Exception e)` en `convertSingleFile()` — Captura cualquier excepción de forma genérica. Se podría ser más específico.

#### 6. `HelloController.java` — Catch de `Exception` demasiado amplio
- **Línea 95**: `catch (Exception e)` en `selectFolder()` — Catch genérico.
- **Línea 225**: `catch (Exception e)` en `showAlert()` — Catch genérico.

---

### Resumen
| Archivo | Warning | Severidad |
|---|---|---|
| `HelloController.java:17` | Import sin usar (`java.util.List`) | Bajo |
| `module-info.java:5,7-11` | 6 módulos `requires` sin usar | Medio |
| `build.gradle.kts:35` | `javafx.swing` en módulos sin usar | Medio |
| `build.gradle.kts:39-49` | 5 dependencias sin usar | Medio |
| `Mp3ConversionService.java:135` | Catch `Exception` genérico | Bajo |
| `HelloController.java:95,225` | Catch `Exception` genérico (2 casos) | Bajo |