// Portfolio mp3Converter: proyecto raiz multi-modulo Gradle.
// Modulos: core (libreria compartida), app-v1-desktop (v1.0.0), app-v2-freemium
// (fase 2+), backend-saas (stub vacio de la fase 7; docs/saas/ARCHITECTURE.md).
rootProject.name = "mp3Converter"

include("core", "app-v1-desktop", "app-v2-freemium", "backend-saas")
