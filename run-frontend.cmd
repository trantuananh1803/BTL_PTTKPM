@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-frontend.ps1"
exit /b %ERRORLEVEL%
