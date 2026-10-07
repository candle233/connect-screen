@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Install-TouchFix.ps1" %*
set "result=%errorlevel%"
echo.
if not "%result%"=="0" echo Installation failed. Read the message above.
pause
exit /b %result%
