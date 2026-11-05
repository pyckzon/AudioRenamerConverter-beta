# build-exe.ps1 - Compila el proyectos por variante y genera el ejecutable .exe para Windows.
#
# Uso:
#   .\build-exe.ps1              -> genera el .exe de la variante por defecto (v1)
#   .\build-exe.ps1 -App v2      -> genera el .exe del proyecto freemium
#   .\build-exe.ps1 -Installer   -> ademas intenta generar el instalador .msi (requiere WiX Toolset)
#
param(
    [ValidateSet("v1", "v2")]
    [string]$App = "v1",
    [switch]$Installer
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$projects = @{
    "v1" = "app-v1-desktop"
    "v2" = "app-v2-freemium"
}

$projectDir = Join-Path $PSScriptRoot $projects[$App]
if (-not (Test-Path $projectDir)) {
    Write-Host "La variante '$App' (proyecto '$($projects[$App])') no existe todavia en este repositorio." -ForegroundColor Red
    exit 1
}

Write-Host "=== 1/3 Compilando el proyecto ===" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon clean build
if ($LASTEXITCODE -ne 0) {
    Write-Host "La compilacion fallo. Revisa los errores arriba." -ForegroundColor Red
    exit 1
}

Write-Host "=== 2/3 Generando el ejecutable con jpackage ($App) ===" -ForegroundColor Cyan
$task = ":$($projects[$App]):jpackageImage"
$tasks = @($task)
if ($Installer) { $tasks += ":$($projects[$App]):jpackage" }
& .\gradlew.bat --no-daemon @tasks
if ($LASTEXITCODE -ne 0) {
    Write-Host "El empaquetado fallo. Revisa los errores arriba." -ForegroundColor Red
    exit 1
}

Write-Host "=== 3/3 Verificando el resultado ===" -ForegroundColor Cyan
$exes = Get-ChildItem (Join-Path $projectDir "build\jpackage") -Recurse -Filter "*.exe" -ErrorAction SilentlyContinue
if ($exes) {
    $launcherExe = $exes | Where-Object { $_.Directory.Name -notlike "*runtime*" } | Select-Object -First 1
    $sizeMb = [math]::Round($launcherExe.Length / 1MB, 1)
    Write-Host ""
    Write-Host "EJECUTABLE GENERADO CORRECTAMENTE:" -ForegroundColor Green
    Write-Host "  $($launcherExe.FullName)  (~$sizeMb KB)"
    Write-Host ""
    Write-Host "Puedes ejecutarlo directamente con doble clic, o copiar la carpeta" -ForegroundColor Green
    Write-Host "  $($launcherExe.Directory.FullName)" -ForegroundColor Green
    Write-Host "a cualquier PC con Windows (no requiere Java instalado)." -ForegroundColor Green
} else {
    Write-Host "No se encontro ningun ejecutable en $($projectDir)\build\jpackage. Revisa los errores de arriba." -ForegroundColor Red
    exit 1
}

if ($Installer) {
    $msi = Get-ChildItem (Join-Path $projectDir "build\jpackage") -Filter "*.msi" -ErrorAction SilentlyContinue
    if ($msi) {
        Write-Host ""
        Write-Host "INSTALADOR GENERADO:" -ForegroundColor Green
        $msi | ForEach-Object { Write-Host "  $($_.FullName)" -ForegroundColor Green }
    } else {
        Write-Host ""
        Write-Host "No se genero el instalador .msi. Normalmente se debe a que falta el" -ForegroundColor Yellow
        Write-Host "'WiX Toolset 3' (necesario para instaladores en Windows)." -ForegroundColor Yellow
        Write-Host "El ejecutable independiente si fue generado correctamente." -ForegroundColor Yellow
    }
}

exit 0
