// Portfolio mp3Converter: proyecto raiz multi-modulo Gradle.
// Modulos: core (libreria compartida), app-v1-desktop (v1.0.0), app-v2-freemium
// (fase 2+), backend-saas (Fase 8 real), app-v3-saas (Fase 9: fork de v2 con
// FeatureGate por suscripcion al backend SaaS).
rootProject.name = "mp3Converter"

include("core", "app-v1-desktop", "app-v2-freemium", "backend-saas", "app-v3-saas")
