$ErrorActionPreference = 'Stop'
$sdk = 'C:\Users\LOCAL_USER\Desktop\android-sdk'
$env:JAVA_HOME = Join-Path $sdk 'jdk17\jdk-17.0.20.1+1'
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$bt = Join-Path $sdk 'build-tools\36.1.0'
$android = Join-Path $sdk 'platforms\android-34\android.jar'
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Force build\classes,build\dex,build\permission,build\permission-dex,build\lib\arm64-v8a | Out-Null
& wsl.exe --cd $PSScriptRoot --exec /usr/sbin/zig cc -target aarch64-linux-musl -shared -nostdlib -fPIC -fno-stack-protector -O2 '-Wl,-soname,libusbguard.so' usbguard.c -o build/lib/arm64-v8a/libusbguard.so
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
