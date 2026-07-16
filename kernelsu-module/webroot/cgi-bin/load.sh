#!/system/bin/sh
CONFIG_FILE=/data/local/tmp/iosclockblur/config.json

printf "Content-Type: application/json\r\n\r\n"

if [ -f "$CONFIG_FILE" ]; then
  cat "$CONFIG_FILE"
else
  # Must match Prefs.kt DEFAULT_* constants.
  printf '{"enabled":true,"blur_radius":12,"downscale":6,"tint_alpha":170,"include_date":false,"force_software_layer":false}'
fi
