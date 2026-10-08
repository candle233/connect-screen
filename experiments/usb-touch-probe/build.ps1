param(
    [string]$SdkDirectory = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WslZigPath = '/usr/sbin/zig'
)
$ErrorActionPreference = 'Stop'
if (!$SdkDirectory -or !(Test-Path -LiteralPath $SdkDirectory -PathType Container)) {
    throw 'Supply -SdkDirectory or set ANDROID_SDK_ROOT to your Android SDK directory.'
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Supply -JavaHome or set JAVA_HOME to your JDK 17 directory.'
}
$sdk = (Resolve-Path -LiteralPath $SdkDirectory).Path
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$bt = Join-Path $sdk 'build-tools\36.1.0'
$android = Join-Path $sdk 'platforms\android-34\android.jar'
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Force build\classes,build\dex,build\permission,build\permission-dex,build\lib\arm64-v8a | Out-Null
& wsl.exe --cd $PSScriptRoot --exec $WslZigPath cc -target aarch64-linux-musl -shared -nostdlib -fPIC -fno-stack-protector -O2 '-Wl,-soname,libusbguard.so' usbguard.c -o build/lib/arm64-v8a/libusbguard.so
if ($LASTEXITCODE) { throw 'Native guard build failed' }
& "$javaBin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $android -d build\classes @(Get-ChildItem src -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE) { throw 'javac failed' }
& "$bt\d8.bat" --lib $android --min-api 29 --output build\dex @(Get-ChildItem build\classes -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE) { throw 'd8 failed' }
& "$bt\aapt2.exe" link -I $android --manifest AndroidManifest.xml -o build\probe-unsigned.apk
if ($LASTEXITCODE) { throw 'aapt failed' }
& "$javaBin\jar.exe" uf build\probe-unsigned.apk -C build\dex classes.dex -C build lib
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out build\usb-touch-probe.apk build\probe-unsigned.apk
if ($LASTEXITCODE) { throw 'sign failed' }
& "$javaBin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $android -d build\permission UsbPermission.java
& "$bt\d8.bat" --lib $android --min-api 29 --output build\permission-dex build\permission\UsbPermission.class
if ($LASTEXITCODE) { throw 'permission helper failed' }
