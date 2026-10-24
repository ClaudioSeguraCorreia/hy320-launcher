@echo off
rem Abre o instalar.ps1 sem precisar mudar a politica de scripts do Windows
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar.ps1"
pause
