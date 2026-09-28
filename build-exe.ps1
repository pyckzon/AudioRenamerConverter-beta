$ErrorActionPreference = "Stop"

Write-Host "Compilando..."
mvn clean package

Write-Host "Creando EXE..."
New-Item -ItemType Directory -Force -Path "dist" | Out-Null

jpackage `
  --type exe `
  --name AudioRenamerConverter `
  --input target `
  --main-jar audio-renamer-converter-1.0.0.jar `
  --main-class com.casl.audiotools.Main `
  --win-console `
  --dest dist

Write-Host ""
Write-Host "Listo. Revisa la carpeta dist."
