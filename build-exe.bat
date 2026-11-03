@echo off
rem Compila el proyecto y genera el .exe (llama a build-exe.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1" %*
pause
