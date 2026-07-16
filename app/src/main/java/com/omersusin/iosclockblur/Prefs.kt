package com.omersusin.iosclockblur

object Prefs {
    const val FILE_NAME = "iosclockblur_prefs"
    const val PACKAGE_NAME = "com.omersusin.iosclockblur"

    const val KEY_ENABLED = "enabled"
    const val KEY_BLUR_RADIUS = "blur_radius"
    const val KEY_DOWNSCALE = "downscale"
    const val KEY_INCLUDE_DATE = "include_date"
    const val KEY_FORCE_SOFTWARE_LAYER = "force_software_layer"
    // 0-220 (out of 255). Real frosted-glass materials always bake in a
    // translucent white layer over the blur - without it, text becomes
    // nearly invisible against low-contrast backgrounds (e.g. plain sky).
    const val KEY_TINT_ALPHA = "tint_alpha"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_BLUR_RADIUS = 12
    const val DEFAULT_DOWNSCALE = 6
    const val DEFAULT_INCLUDE_DATE = false
    const val DEFAULT_FORCE_SOFTWARE_LAYER = false
    // Empirically tuned by the user against a dark night-sky wallpaper,
    // matched side-by-side against a real iOS depth-effect screenshot.
    // Low tint values leave text nearly invisible on dark/busy wallpapers.
    const val DEFAULT_TINT_ALPHA = 170

    // Sent by the settings Activity so the already-running SystemUI hook can
    // re-read config and repaint immediately, without needing a reboot.
    const val ACTION_SETTINGS_CHANGED = "com.omersusin.iosclockblur.SETTINGS_CHANGED"

    // Not a real secret - just filters out accidental/unrelated broadcasts
    // to this custom action, since the receiver has to be RECEIVER_EXPORTED
    // (Android 13+) for a different-UID sender to reach it at all.
    const val EXTRA_TOKEN = "token"
    const val TOKEN = "iosclockblur-x9f3-settings-v1"

    // Root-written fallback config. XSharedPreferences (private app data read
    // cross-UID) can be blocked by SELinux on hardened ROMs; /data/local/tmp
    // is readable by system_app/platform_app domains on essentially every
    // stock AOSP-derived sepolicy, so it's a much more reliable channel -
    // but it only gets written if the user grants root to this app. If they
    // never do, this file simply never appears and XSharedPreferences is
    // used as before.
    const val ROOT_CONFIG_DIR = "/data/local/tmp/iosclockblur"
    const val ROOT_CONFIG_PATH = "$ROOT_CONFIG_DIR/config.json"
}
