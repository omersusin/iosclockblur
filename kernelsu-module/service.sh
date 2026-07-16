#!/system/bin/sh
# iOS Blur Clock WebUI service
# Starts a loopback-only HTTP server (busybox httpd) so the WebUI can
# read/write /data/local/tmp/iosclockblur/config.json - the same file
# ClockBlurHook.kt (LSPosed side) already reads as its primary config
# source. This script does NOT touch the render/blur pipeline at all.

MODDIR=${MODDIR:-${0%/*}}
PORT=8765

BB=/data/adb/ksu/bin/busybox
[ -x "$BB" ] || BB=busybox

CONFIG_DIR=/data/local/tmp/iosclockblur
mkdir -p "$CONFIG_DIR"
chmod 755 "$CONFIG_DIR"

chmod +x "$MODDIR/webroot/cgi-bin/"*.sh 2>/dev/null

# Bind to loopback only - not reachable from other devices on the same
# network, only from a browser on this phone.
"$BB" httpd -f -p 127.0.0.1:$PORT -h "$MODDIR/webroot" &
