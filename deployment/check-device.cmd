@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Check-Device.ps1" %*
set "result=%errorlevel%"
echo.
pause
exit /b %result%
