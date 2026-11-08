@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-and-run.ps1"
exit /b %ERRORLEVEL%
