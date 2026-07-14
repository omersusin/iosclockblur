package com.omersusin.iosclockblur

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)

        if (!prefs.contains(Prefs.KEY_ENABLED)) {
            prefs.edit()
                .putBoolean(Prefs.KEY_ENABLED, Prefs.DEFAULT_ENABLED)
                .putInt(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS)
                .putInt(Prefs.KEY_DOWNSCALE, Prefs.DEFAULT_DOWNSCALE)
                .putBoolean(Prefs.KEY_INCLUDE_DATE, Prefs.DEFAULT_INCLUDE_DATE)
                .putBoolean(Prefs.KEY_FORCE_SOFTWARE_LAYER, Prefs.DEFAULT_FORCE_SOFTWARE_LAYER)
                .apply()
        }
        makeWorldReadable()
        writeRootConfig() // best-effort; silently does nothing if root isn't granted

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 96)
        }

        addTitle(root, "iOS Blur Clock")
        addInfo(root)

        addSwitch(
            root, "Etkin",
            prefs.getBoolean(Prefs.KEY_ENABLED, Prefs.DEFAULT_ENABLED)
        ) { checked -> save { it.putBoolean(Prefs.KEY_ENABLED, checked) } }

        addSeekBar(
            root, "Blur siddeti",
            prefs.getInt(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS),
            min = 2, max = 24
        ) { value -> save { it.putInt(Prefs.KEY_BLUR_RADIUS, value) } }

        addSeekBar(
            root, "Kucultme orani (yuksek = daha yumusak, daha hizli)",
            prefs.getInt(Prefs.KEY_DOWNSCALE, Prefs.DEFAULT_DOWNSCALE),
            min = 3, max = 12
        ) { value -> save { it.putInt(Prefs.KEY_DOWNSCALE, value) } }

        addSwitch(
            root, "Tarih yazisini da bulaniklastir",
            prefs.getBoolean(Prefs.KEY_INCLUDE_DATE, Prefs.DEFAULT_INCLUDE_DATE)
        ) { checked -> save { it.putBoolean(Prefs.KEY_INCLUDE_DATE, checked) } }

        addSwitch(
            root, "Uyumluluk modu (yazi/gorsel bozuluyorsa ac)",
            prefs.getBoolean(Prefs.KEY_FORCE_SOFTWARE_LAYER, Prefs.DEFAULT_FORCE_SOFTWARE_LAYER)
        ) { checked -> save { it.putBoolean(Prefs.KEY_FORCE_SOFTWARE_LAYER, checked) } }

        addNote(root)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun save(block: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.apply()
        makeWorldReadable()
        writeRootConfig()
        sendBroadcast(
            Intent(Prefs.ACTION_SETTINGS_CHANGED)
                .setPackage("com.android.systemui")
                .putExtra(Prefs.EXTRA_TOKEN, Prefs.TOKEN)
        )
    }

    /**
     * SystemUI runs as a different UID, so it can't use the normal
     * SharedPreferences API to read our file - it reads the raw XML via
     * XSharedPreferences instead. That only works if the file (and the
     * directories leading to it) are readable by others. Standard, decade-old
     * Xposed pattern; can still be blocked by SELinux on hardened ROMs (see
     * writeRootConfig() for the more reliable fallback channel).
     */
    private fun makeWorldReadable() {
        try {
            val dataDir = File(applicationInfo.dataDir)
            dataDir.setExecutable(true, false)
            val prefsDir = File(dataDir, "shared_prefs")
            prefsDir.setExecutable(true, false)
            prefsDir.setReadable(true, false)
            val prefsFile = File(prefsDir, "${Prefs.FILE_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        } catch (t: Throwable) {
            // Best effort - if this fails, XSharedPreferences just won't work
            // and the hook falls back to its built-in defaults (or the root
            // config file below, if that's available).
        }
    }

    /**
     * Best-effort: write the current settings as JSON to a root-owned,
     * world-readable location that isn't subject to per-app SELinux data
     * isolation. Requires the user to grant root to this app (KernelSU will
     * prompt on first call); if they never do, `su` simply fails and this is
     * a silent no-op - XSharedPreferences remains the read path in the hook.
     */
    private fun writeRootConfig() {
        val json = JSONObject().apply {
            put("enabled", prefs.getBoolean(Prefs.KEY_ENABLED, Prefs.DEFAULT_ENABLED))
            put("blur_radius", prefs.getInt(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS))
            put("downscale", prefs.getInt(Prefs.KEY_DOWNSCALE, Prefs.DEFAULT_DOWNSCALE))
            put("include_date", prefs.getBoolean(Prefs.KEY_INCLUDE_DATE, Prefs.DEFAULT_INCLUDE_DATE))
            put("force_software_layer", prefs.getBoolean(Prefs.KEY_FORCE_SOFTWARE_LAYER, Prefs.DEFAULT_FORCE_SOFTWARE_LAYER))
        }.toString()

        Thread {
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "mkdir -p ${Prefs.ROOT_CONFIG_DIR} && " +
                            "cat > ${Prefs.ROOT_CONFIG_PATH} && " +
                            "chmod 644 ${Prefs.ROOT_CONFIG_PATH} && " +
                            "chmod 755 ${Prefs.ROOT_CONFIG_DIR}"
                    )
                )
                proc.outputStream.use { it.write(json.toByteArray()); it.flush() }
                proc.waitFor()
            } catch (t: Throwable) {
                // No root granted / denied / su missing - silently ignore.
            }
        }.start()
    }

    // ---- tiny manual UI builder helpers (kept dependency-free, no XML layouts) ----

    private fun addTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 32)
        })
    }

    private fun addInfo(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "Degisiklikler SystemUI'ye hemen bildirilir. Gorunmezse " +
                "kilit ekranini bir kere ac/kapa ya da telefonu yeniden baslat.\n\n" +
                "Ilk acilista bir 'Superuser istegi' cikabilir - onaylarsan " +
                "ayarlar daha guvenilir bir kanaldan SystemUI'ye ulasir. " +
                "Onaylamazsan da modul calismaya devam eder, sadece ayarlar " +
                "biraz daha kirilgan bir yoldan (XSharedPreferences) tasinir."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 32)
        })
    }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        val sw = Switch(this).apply { isChecked = initial }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        parent.addView(row)
    }

    private fun addSeekBar(
        parent: LinearLayout,
        title: String,
        initial: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 24, 0, 0)
        })
        val valueLabel = TextView(this).apply {
            text = initial.toString()
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        parent.addView(valueLabel)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, max - min)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + min
                valueLabel.text = value.toString()
                if (fromUser) onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        parent.addView(seek)
    }

    private fun addNote(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "\nKurulum: LSPosed Yoneticisi'nde bu modulu etkinlestir, " +
                "kapsam System UI, sonra reboot.\n\n" +
                "Debug: su -c logcat -s iOSClockBlur:*"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 32, 0, 0)
        })
    }
}
