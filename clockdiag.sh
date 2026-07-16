#!/data/data/com.termux/files/usr/bin/bash
# Clock/Depth-Effect ROM diagnostic dump - portable, minimal dependencies.

pkg install -y unzip >/dev/null 2>&1

OUT="$HOME/clockdiag_$(getprop ro.product.model 2>/dev/null | tr ' /' '__')_$(date +%Y%m%d_%H%M%S).txt"

have_strings() { command -v strings >/dev/null 2>&1; }
extract_strings() {
  if have_strings; then
    strings "$1"
  else
    tr -c '[:print:]\n' '\n' < "$1" | grep -E '.{6,}'
  fi
}

{
echo "===================================================="
echo "CLOCK / DEPTH-EFFECT DIAGNOSTIC DUMP"
echo "Date: $(date)"
echo "===================================================="

echo ""
echo "=== 1. DEVICE / ROM ==="
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.product.marketname
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.build.display.id
getprop ro.build.version.incremental
echo "--- ROM fingerprint hints ---"
getprop | grep -iE "miui|hyperos|coloros|oxygenos|oneui|realme|funtouch|flyme|lineage|crdroid|pixelexperience|miui.notch"

echo ""
echo "=== 2. CLOCK/LOCKSCREEN/DEPTH/BLUR SETTINGS (secure) ==="
su -c 'settings list secure' 2>/dev/null | grep -iE "clock|lockscreen|keyguard|depth|wallpaper|blur|glass"

echo ""
echo "=== 3. CLOCK/LOCKSCREEN/DEPTH/BLUR SETTINGS (system) ==="
su -c 'settings list system' 2>/dev/null | grep -iE "clock|lockscreen|keyguard|depth|wallpaper|blur|glass"

echo ""
echo "=== 4. CLOCK/LOCKSCREEN/DEPTH/BLUR SETTINGS (global) ==="
su -c 'settings list global' 2>/dev/null | grep -iE "clock|lockscreen|keyguard|depth|wallpaper|blur|glass"

echo ""
echo "=== 5. SYSTEMUI PACKAGE ==="
SYSUI_PATH=$(su -c 'pm path com.android.systemui' 2>/dev/null | head -1 | sed 's/package://')
echo "Path: $SYSUI_PATH"
su -c "dumpsys package com.android.systemui" 2>/dev/null | grep -E "versionName|versionCode"

echo ""
echo "=== 6. SYSTEMUI - CLOCK/KEYGUARD/DEPTH CLASS NAME SEARCH ==="
if [ -n "$SYSUI_PATH" ]; then
  su -c "cp '$SYSUI_PATH' /data/local/tmp/sysui_dump.apk && chmod 644 /data/local/tmp/sysui_dump.apk" 2>/dev/null
  cp /data/local/tmp/sysui_dump.apk "$HOME/sysui_dump.apk" 2>/dev/null
  if [ -f "$HOME/sysui_dump.apk" ]; then
    mkdir -p "$HOME/sysui_extract"
    unzip -o "$HOME/sysui_dump.apk" 'classes*.dex' -d "$HOME/sysui_extract" >/dev/null 2>&1
    for f in "$HOME/sysui_extract"/classes*.dex; do
      [ -f "$f" ] && extract_strings "$f" | grep -iE "^Lcom/.*[Cc]lock|^Lcom/.*[Kk]eyguard|^Lcom/.*[Dd]epth|^Lcom/.*[Bb]lur"
    done | sort -u
    rm -rf "$HOME/sysui_extract" "$HOME/sysui_dump.apk"
  else
    echo "(APK could not be copied out - root/SELinux restricted)"
  fi
else
  echo "(SystemUI path not found)"
fi

echo ""
echo "=== 7. DEFAULT LAUNCHER (depth-effect wallpaper logic often lives here) ==="
LAUNCHER_PKG=$(su -c 'cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN' 2>/dev/null | grep packageName | head -1 | sed 's/.*packageName=//')
echo "Default launcher: $LAUNCHER_PKG"
if [ -n "$LAUNCHER_PKG" ]; then
  LAUNCHER_PATH=$(su -c "pm path $LAUNCHER_PKG" 2>/dev/null | head -1 | sed 's/package://')
  echo "Path: $LAUNCHER_PATH"
  if [ -n "$LAUNCHER_PATH" ]; then
    su -c "cp '$LAUNCHER_PATH' /data/local/tmp/launcher_dump.apk && chmod 644 /data/local/tmp/launcher_dump.apk" 2>/dev/null
    cp /data/local/tmp/launcher_dump.apk "$HOME/launcher_dump.apk" 2>/dev/null
    if [ -f "$HOME/launcher_dump.apk" ]; then
      mkdir -p "$HOME/launcher_extract"
      unzip -o "$HOME/launcher_dump.apk" 'classes*.dex' -d "$HOME/launcher_extract" >/dev/null 2>&1
      for f in "$HOME/launcher_extract"/classes*.dex; do
        [ -f "$f" ] && extract_strings "$f" | grep -iE "depth.*wallpaper|wallpaper.*depth|portrait.*wallpaper|segmentation"
      done | sort -u
      rm -rf "$HOME/launcher_extract" "$HOME/launcher_dump.apk"
    fi
  fi
fi

echo ""
echo "=== 8. PACKAGES MATCHING CLOCK/WALLPAPER/THEME/DEPTH KEYWORDS ==="
su -c 'pm list packages' 2>/dev/null | grep -iE "wallpaper|theme|clock|magazine|depth"

echo ""
echo "=== 9. ROOT / XPOSED FRAMEWORK ==="
echo "--- KSU/Magisk modules ---"
su -c 'ls /data/adb/modules' 2>/dev/null
su -c 'ls /data/adb/ksu/modules' 2>/dev/null
su -c 'getprop ro.kernelsu.version' 2>/dev/null
su -c 'getprop ro.magisk.versionCode' 2>/dev/null
echo "--- Xposed/LSPosed related packages ---"
pm list packages 2>/dev/null | grep -iE "lsposed|zygisk|magisk|xposed"

echo ""
echo "=== 10. DISPLAY ==="
su -c 'wm size' 2>/dev/null
su -c 'wm density' 2>/dev/null

echo ""
echo "===================================================="
echo "END OF DUMP"
echo "===================================================="
} > "$OUT" 2>&1

su -c "mkdir -p /sdcard/Download" 2>/dev/null
su -c "cp '$OUT' /sdcard/Download/$(basename "$OUT")" 2>/dev/null
su -c "chmod 644 /sdcard/Download/$(basename "$OUT")" 2>/dev/null

echo "Yazildi: /sdcard/Download/$(basename "$OUT")"
