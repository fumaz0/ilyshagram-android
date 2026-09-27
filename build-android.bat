@echo off
setlocal
set "ROOT=%~dp0"
set "PS1=%ROOT%scripts\build.ps1"

if not exist "%PS1%" (
  echo [ERROR] Missing script: %PS1%
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "ERR=%ERRORLEVEL%"
if %ERR% neq 0 pause
exit /b %ERR%
