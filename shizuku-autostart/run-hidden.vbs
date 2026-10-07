' Launches Start-Shizuku.ps1 (same directory) with all forwarded arguments,
' without a visible console window. Used by the ShizukuAutoStart scheduled task.
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
forwarded = ""
For Each a In WScript.Arguments
    forwarded = forwarded & " """ & a & """"
Next
shell.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File """ & dir & "\Start-Shizuku.ps1""" & forwarded, 0, True
