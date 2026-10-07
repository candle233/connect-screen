param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$OutputPath
)
$ErrorActionPreference='Stop'
function Read-Adb([string[]]$Arguments) {
    $result=& adb -s $Serial @Arguments
    if ($LASTEXITCODE) { throw "ADB read failed: $($Arguments -join ' ')" }
    return ($result -join "`n").Trim()
}
[xml]$prefs=Read-Adb @('shell','run-as','com.gitee.connect_screen.touchfix','cat','shared_prefs/usb_touch.xml')
$values=[ordered]@{}
foreach ($entry in $prefs.map.ChildNodes) {
    if (!$entry.Attributes['name']) { continue }
    $key=$entry.GetAttribute('name')
    $values[$key]=if ($entry.LocalName -eq 'string') { $entry.InnerText } else { $entry.GetAttribute('value') }
}
$processes=Read-Adb @('shell','ps','-A')
$evidence=[ordered]@{
    capturedUtc=[DateTime]::UtcNow.ToString('o')
    serial=$Serial
    model=(Read-Adb @('shell','getprop','ro.product.model'))
    bootCount=(Read-Adb @('shell','settings','get','global','boot_count'))
    bootId=(Read-Adb @('shell','cat','/proc/sys/kernel/random/boot_id'))
    shizukuServerPresent=[bool]($processes -match '(?m)\bshizuku_server\s*$')
    legacyRelayPresent=[bool]($processes -match '(?m)\btouch_relay\s*$')
    enabledAccessibility=(Read-Adb @('shell','settings','get','secure','enabled_accessibility_services'))
    touchState=$values
}
$json=$evidence | ConvertTo-Json -Depth 4
if ($OutputPath) { $json | Set-Content -LiteralPath $OutputPath -Encoding utf8 }
$json
