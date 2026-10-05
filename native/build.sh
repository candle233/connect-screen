#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p bin
if command -v zig >/dev/null 2>&1; then
    zig cc -target aarch64-linux-musl -static -O2 -Wall -Wextra -Werror touch_relay.c -o bin/touch_relay
else
    aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror touch_relay.c -o bin/touch_relay
fi
file bin/touch_relay
