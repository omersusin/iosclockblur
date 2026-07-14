package com.omersusin.iosclockblur

object Prefs {
    const val FILE_NAME = "iosclockblur_prefs"
    const val PACKAGE_NAME = "com.omersusin.iosclockblur"

    const val KEY_ENABLED = "enabled"
    const val KEY_BLUR_RADIUS = "blur_radius"
    const val KEY_DOWNSCALE = "downscale"
    const val KEY_INCLUDE_DATE = "include_date"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_BLUR_RADIUS = 10
    const val DEFAULT_DOWNSCALE = 6
    const val DEFAULT_INCLUDE_DATE = false

    // Sent by the settings Activity so the already-running SystemUI hook can
    // re-read prefs and repaint immediately, without needing a reboot.
    const val ACTION_SETTINGS_CHANGED = "com.omersusin.iosclockblur.SETTINGS_CHANGED"
}
