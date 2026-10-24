@echo off
rem Abre o desfazer.ps1 sem precisar mudar a politica de scripts do Windows
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0desfazer.ps1"
pause
