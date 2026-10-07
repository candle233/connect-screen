# Removes the scheduled task created by Register-ShizukuAutoStart.ps1.
param([string]$TaskName = 'ShizukuAutoStart')

$ErrorActionPreference = 'Stop'

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Scheduled task '$TaskName' removed."
} else {
    Write-Host "Scheduled task '$TaskName' not found; nothing to remove."
}
Write-Host 'Log files in %LOCALAPPDATA%\ShizukuAutoStart were kept.'
