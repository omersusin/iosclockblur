package com.omersusin.iosclockblur

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * This app is now ONLY the LSPosed hook carrier (see ClockBlurHook.kt).
 * Settings live in the separate "iOS Blur Clock - Ayarlar (WebUI)" KernelSU
 * module, opened from KSU Manager or via the button below
 * (http://127.0.0.1:8765, served by that module's service.sh).
 *
 * Nothing here writes settings anymore - no SharedPreferences, no su exec.
 * ClockBlurHook.kt reads /data/local/tmp/iosclockblur/config.json directly
 * (written by the WebUI module), with XSharedPreferences as a secondary
 * fallback that nothing currently writes to but is harmless to keep.
 */
class MainActivity : Activity() {

    private val webUiUrl = "http://127.0.0.1:8765"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "iOS Blur Clock"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Bu uygulama artık sadece SystemUI hook'unu taşıyor. " +
                "Ayarlar (blur şiddeti, parlaklık, vb.) ayrı bir KernelSU " +
                "modülünün WebUI'sinden yönetiliyor.\n\n" +
                "Kurulum:\n" +
                "1) Bu modülü LSPosed Yöneticisi'nde etkinleştir, kapsam " +
                "System UI, reboot.\n" +
                "2) \"iOS Blur Clock - Ayarlar (WebUI)\" KernelSU modülünü " +
                "ayrıca kur ve etkinleştir, reboot.\n" +
                "3) KernelSU Yöneticisi > Modüller > o modül > WebUI ikonuna " +
                "dokun, ya da aşağıdaki butonu kullan.\n\n" +
                "Debug: su -c logcat -s iOSClockBlur:*"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "Ayarları Aç (WebUI)"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUiUrl)))
                } catch (t: Throwable) {
                    // No browser resolved it - fall back to just showing the URL.
                }
            }
        })

        root.addView(TextView(this).apply {
            text = "\nEğer buton tarayıcı açmazsa, adres çubuğuna elle yaz:\n$webUiUrl"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.START
            setPadding(0, 16, 0, 0)
        })

        setContentView(root)
    }
}
