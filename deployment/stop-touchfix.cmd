@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-TouchFix.ps1" %*
set "result=%errorlevel%"
echo.
pause
exit /b %result%
