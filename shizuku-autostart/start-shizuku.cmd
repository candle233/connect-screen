@echo off
rem Manual check/start of the Shizuku server on the connected phone.
rem Also used to verify the auto-start setup: the output is written to
rem %LOCALAPPDATA%\ShizukuAutoStart\autostart.log as well.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-Shizuku.ps1" -WaitSeconds 120
pause
