#!/system/bin/sh
CONFIG_DIR=/data/local/tmp/iosclockblur
CONFIG_FILE=$CONFIG_DIR/config.json

mkdir -p "$CONFIG_DIR"

# Read exactly CONTENT_LENGTH bytes from stdin (POSIX-portable, avoids
# 'read -n' which isn't available in every /system/bin/sh implementation).
dd bs=1 count="${CONTENT_LENGTH:-0}" 2>/dev/null > "$CONFIG_FILE"

chmod 644 "$CONFIG_FILE"
chmod 755 "$CONFIG_DIR"

# Same broadcast + token the settings Activity already used - the running
# ClockBlurHook.kt receiver doesn't care whether the sender is an app or
# a root shell, only that the token matches.
am broadcast -a com.omersusin.iosclockblur.SETTINGS_CHANGED \
    --es token "iosclockblur-x9f3-settings-v1" \
    -p com.android.systemui >/dev/null 2>&1

printf "Content-Type: text/plain\r\n\r\nOK"
