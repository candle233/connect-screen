$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    foreach ($target in @(
        @{ Triple='aarch64-linux-musl'; Abi='arm64-v8a' },
        @{ Triple='arm-linux-musleabi'; Abi='armeabi-v7a' }
    )) {
        $output = 'app/src/main/jniLibs/' + $target.Abi + '/libtouchusb.so'
        & wsl.exe --cd $projectRoot --exec /usr/sbin/zig cc -target $target.Triple -shared -nostdlib -fPIC -fomit-frame-pointer -fno-stack-protector -O2 '-Wl,-soname,libtouchusb.so' native/usb_touch_guard.c -o $output
        if ($LASTEXITCODE) { throw "USB driver build failed: $($target.Abi)" }
    }
} finally { Pop-Location }
