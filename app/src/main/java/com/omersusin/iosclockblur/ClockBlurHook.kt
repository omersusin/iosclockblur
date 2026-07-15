package com.omersusin.iosclockblur

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextClock
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Makes the ROM's built-in lockscreen "Custom Clock Style" (ClockStyle.java, the
 * "Ozel Saat Stili" / Sternum-ios-etc layouts) render with a real frosted-glass
 * look: each TextClock's Paint gets a BitmapShader built from a blurred,
 * screen-aligned crop of the current wallpaper, instead of a flat color.
 *
 * Two hook points on ClockStyle:
 *  - updateClockView(): full rebuild (style/size change, first inflate).
 *  - applyClockAlpha(): also fires on dozing (AOD) transitions even when the
 *    view isn't rebuilt - used here to strip the shader while dozing, since
 *    the doze background is black and there's no wallpaper behind the clock.
 *
 * Config is read from (in order): a root-written JSON file at
 * Prefs.ROOT_CONFIG_PATH (reliable but requires the user to grant root to
 * the settings app), then XSharedPreferences (no root needed, but can be
 * blocked by SELinux on some ROMs), then hardcoded defaults. Never throws.
 */
class ClockBlurHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "iOSClockBlur"
        private const val TARGET_PKG = "com.android.systemui"
        private const val CLOCK_CLASS = "com.android.systemui.clocks.ClockStyle"
        private const val BLUR_PASSES = 3
    }

    private data class Config(
        val enabled: Boolean,
        val blurRadius: Int,
        val downscale: Int,
        val includeDate: Boolean,
        val forceSoftwareLayer: Boolean,
        val tintAlpha: Int,
    )

    // All of the fields below are only ever touched on SystemUI's main thread:
    // hook callbacks run there, and the background blur executor hands its
    // result back via mainHandler.post{}. No locking needed.
    private var cachedBlurred: Bitmap? = null
    private var cachedForWidth = 0
    private var cachedForHeight = 0
    private var cachedForRadius = -1
    private var cachedForDownscale = -1
    private var cachedForWallpaperId = Int.MIN_VALUE
    private var cachedForTint = -1
    private var building = false

    private val blurExecutor = Executors.newSingleThreadExecutor()

    // MUST be lazy: LSPosed constructs this class in every zygote-forked
    // process (system_server, this app, SystemUI, ...) just to check whether
    // handleLoadPackage() applies - before that process's main Looper is
    // necessarily prepared yet. Eagerly calling Handler(Looper.getMainLooper())
    // as a field initializer crashes the constructor itself (NPE on a null
    // Looper) in every process, which silently prevents handleLoadPackage()
    // from ever running anywhere. Deferring to first real use (inside
    // SystemUI, well after its Looper exists) avoids this entirely.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var receiversRegistered = false
    private val activeClockViews = mutableListOf<WeakReference<View>>()

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        val clockStyleClass = XposedHelpers.findClassIfExists(CLOCK_CLASS, lpparam.classLoader)
        if (clockStyleClass == null) {
            XposedBridge.log("$TAG: ClockStyle class not found - ROM layout differs from expected")
            return
        }

        XposedBridge.hookAllMethods(clockStyleClass, "updateClockView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    onClockRebuilt(param.thisObject as View)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: onClockRebuilt failed: $t")
                }
            }
        })

        val alphaMethod = XposedHelpers.findMethodExactIfExists(clockStyleClass, "applyClockAlpha")
        if (alphaMethod != null) {
            XposedBridge.hookMethod(alphaMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onDozeStateMaybeChanged(param.thisObject as View)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: onDozeStateMaybeChanged failed: $t")
                    }
                }
            })
        } else {
            XposedBridge.log("$TAG: applyClockAlpha not found - AOD shader-clearing only via full rebuilds")
        }

        XposedBridge.log("$TAG: hooked $CLOCK_CLASS successfully")
    }

    // ---- Hook entry points ----------------------------------------------------

    private fun onClockRebuilt(clockStyleView: View) {
        registerReceiversOnce(clockStyleView.context)
        rememberView(clockStyleView)

        if (clockStyleView.width > 0 && clockStyleView.height > 0) {
            applyGlassEffect(clockStyleView)
        } else {
            // First inflate: view has no size yet, wait for the layout pass so
            // getLocationOnScreen() below returns real coordinates.
            clockStyleView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        clockStyleView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        applyGlassEffect(clockStyleView)
                    }
                }
            )
        }
    }

    private fun onDozeStateMaybeChanged(clockStyleView: View) {
        registerReceiversOnce(clockStyleView.context)
        rememberView(clockStyleView)
        applyGlassEffect(clockStyleView)
    }

    private fun rememberView(view: View) {
        activeClockViews.removeAll { it.get() == null || it.get() === view }
        activeClockViews.add(WeakReference(view))
    }

    // ---- Core apply logic -------------------------------------------------

    private fun applyGlassEffect(clockStyleView: View) {
        val context = clockStyleView.context ?: return
        val config = loadConfig()
        val (textClocks, styledTextViews) = resolveViews(clockStyleView)

        if (!config.enabled) {
            clearShaders(textClocks)
            clearShaders(styledTextViews)
            return
        }
        if (textClocks.isEmpty()) return

        val isDozing = try {
            XposedHelpers.getBooleanField(clockStyleView, "isDozing")
        } catch (t: Throwable) {
            false
        }
        if (isDozing) {
            // AOD background is black - no wallpaper behind the clock, and a
            // static glass texture there would look wrong and adds pointless
            // static-content burn-in risk. Just clear back to normal.
            clearShaders(textClocks)
            clearShaders(styledTextViews)
            return
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val blurred = getCachedBlurredWallpaperOrTriggerBuild(
            context, screenW, screenH, config.blurRadius, config.downscale, config.tintAlpha
        ) ?: return // either building async (reapplyToAllKnownViews will repaint) or unavailable (live wallpaper etc.)

        var applied = paintShaderOnto(textClocks, blurred, config.forceSoftwareLayer)
        if (config.includeDate) {
            val dateViews = styledTextViews.filter { it !is TextClock }
            applied += paintShaderOnto(dateViews, blurred, config.forceSoftwareLayer)
        }
        if (applied > 0) {
            XposedBridge.log("$TAG: glass shader applied to $applied view(s)")
        }
    }

    /**
     * Reflection first (fast path, matches this exact ROM build); if the
     * field is missing/renamed/empty (future ROM update), fall back to
     * walking the view tree directly - slower but works regardless of
     * ClockStyle's internal field names.
     */
    private fun resolveViews(clockStyleView: View): Pair<List<View>, List<View>> {
        val reflectedClocks = try {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.getObjectField(clockStyleView, "textClocks") as? ArrayList<View>
        } catch (t: Throwable) {
            null
        }
        val reflectedAll = try {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.getObjectField(clockStyleView, "styledTextViews") as? ArrayList<View>
        } catch (t: Throwable) {
            null
        }

        if (!reflectedClocks.isNullOrEmpty()) {
            return reflectedClocks to (reflectedAll ?: reflectedClocks)
        }

        val walkedClocks = mutableListOf<View>()
        val walkedAll = mutableListOf<View>()
        fun walk(v: View) {
            when {
                v is TextClock -> {
                    walkedClocks += v
                    walkedAll += v
                }
                v is TextView -> walkedAll += v
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(clockStyleView)
        if (walkedClocks.isNotEmpty()) {
            XposedBridge.log("$TAG: reflection fields missing/empty, used view-tree fallback (${walkedClocks.size} clock view(s))")
        }
        return walkedClocks to walkedAll
    }

    private fun paintShaderOnto(views: List<View>, blurred: Bitmap, forceSoftwareLayer: Boolean): Int {
        val loc = IntArray(2)
        var count = 0
        val desiredLayerType = if (forceSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE
        for (v in views) {
            val tv = v as? TextView ?: continue
            tv.getLocationOnScreen(loc)

            val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            // View-local canvas (0,0) is screen (loc[0], loc[1]); we want that
            // local point to sample the full-screen bitmap at (loc[0], loc[1]).
            matrix.setTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            shader.setLocalMatrix(matrix)

            tv.paint.shader = shader
            if (tv.layerType != desiredLayerType) {
                tv.setLayerType(desiredLayerType, null)
            }
            tv.invalidate()
            count++
        }
        return count
    }

    private fun clearShaders(views: List<View>) {
        for (v in views) {
            val tv = v as? TextView ?: continue
            if (tv.paint.shader != null) {
                tv.paint.shader = null
                if (tv.layerType != View.LAYER_TYPE_NONE) {
                    tv.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                tv.invalidate()
            }
        }
    }

    // ---- Receivers ----------------------------------------------------------

    private fun registerReceiversOnce(context: Context) {
        if (receiversRegistered) return
        try {
            val appContext = context.applicationContext

            val wallpaperReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    cachedBlurred = null
                    XposedBridge.log("$TAG: wallpaper changed, cache invalidated")
                    reapplyToAllKnownViews()
                }
            }
            val settingsReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.getStringExtra(Prefs.EXTRA_TOKEN) != Prefs.TOKEN) {
                        XposedBridge.log("$TAG: ignoring settings-changed broadcast with missing/wrong token")
                        return
                    }
                    cachedBlurred = null
                    XposedBridge.log("$TAG: settings changed, reapplying")
                    reapplyToAllKnownViews()
                }
            }

            registerCompat(appContext, wallpaperReceiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
            registerCompat(appContext, settingsReceiver, IntentFilter(Prefs.ACTION_SETTINGS_CHANGED))

            receiversRegistered = true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not register receivers: $t")
        }
    }

    private fun registerCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        // Android 13+ requires an explicit exported/not-exported flag for
        // context-registered receivers, or registerReceiver() throws.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun reapplyToAllKnownViews() {
        val iterator = activeClockViews.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next().get()
            if (view == null) {
                iterator.remove()
            } else {
                applyGlassEffect(view)
            }
        }
    }

    // ---- Config (root JSON preferred, XSharedPreferences fallback) -----------

    private fun loadConfig(): Config {
        readRootConfig()?.let { return it }
        return readXSharedPrefsConfig()
    }

    private fun readRootConfig(): Config? {
        return try {
            val file = File(Prefs.ROOT_CONFIG_PATH)
            if (!file.canRead()) return null
            val json = JSONObject(file.readText())
            Config(
                enabled = json.optBoolean("enabled", Prefs.DEFAULT_ENABLED),
                blurRadius = json.optInt("blur_radius", Prefs.DEFAULT_BLUR_RADIUS).coerceIn(1, 50),
                downscale = json.optInt("downscale", Prefs.DEFAULT_DOWNSCALE).coerceIn(2, 16),
                includeDate = json.optBoolean("include_date", Prefs.DEFAULT_INCLUDE_DATE),
                forceSoftwareLayer = json.optBoolean("force_software_layer", Prefs.DEFAULT_FORCE_SOFTWARE_LAYER),
                tintAlpha = json.optInt("tint_alpha", Prefs.DEFAULT_TINT_ALPHA).coerceIn(0, 220),
            )
        } catch (t: Throwable) {
            null // file doesn't exist yet / root never granted / malformed - fall through silently
        }
    }

    private fun readXSharedPrefsConfig(): Config {
        val xprefs = try {
            XSharedPreferences(Prefs.PACKAGE_NAME, Prefs.FILE_NAME).apply { reload() }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not read XSharedPreferences, using built-in defaults: $t")
            null
        }
        return Config(
            enabled = xprefs?.getBoolean(Prefs.KEY_ENABLED, Prefs.DEFAULT_ENABLED) ?: Prefs.DEFAULT_ENABLED,
            blurRadius = (xprefs?.getInt(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS) ?: Prefs.DEFAULT_BLUR_RADIUS).coerceIn(1, 50),
            downscale = (xprefs?.getInt(Prefs.KEY_DOWNSCALE, Prefs.DEFAULT_DOWNSCALE) ?: Prefs.DEFAULT_DOWNSCALE).coerceIn(2, 16),
            includeDate = xprefs?.getBoolean(Prefs.KEY_INCLUDE_DATE, Prefs.DEFAULT_INCLUDE_DATE) ?: Prefs.DEFAULT_INCLUDE_DATE,
            forceSoftwareLayer = xprefs?.getBoolean(Prefs.KEY_FORCE_SOFTWARE_LAYER, Prefs.DEFAULT_FORCE_SOFTWARE_LAYER)
                ?: Prefs.DEFAULT_FORCE_SOFTWARE_LAYER,
            tintAlpha = (xprefs?.getInt(Prefs.KEY_TINT_ALPHA, Prefs.DEFAULT_TINT_ALPHA) ?: Prefs.DEFAULT_TINT_ALPHA).coerceIn(0, 220),
        )
    }

    // ---- Wallpaper + async blur build -----------------------------------------

    /**
     * Returns the cached bitmap if all cache keys match. Otherwise kicks off
     * (or lets an in-flight) async rebuild and returns null immediately -
     * caller should just skip painting this round; reapplyToAllKnownViews()
     * repaints everything once the build finishes.
     */
    private fun getCachedBlurredWallpaperOrTriggerBuild(
        context: Context, w: Int, h: Int, radius: Int, downscale: Int, tintAlpha: Int
    ): Bitmap? {
        val wm = WallpaperManager.getInstance(context)
        val wallpaperId = try {
            wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
        } catch (t: Throwable) {
            -1
        }

        cachedBlurred?.let {
            if (cachedForWidth == w && cachedForHeight == h &&
                cachedForRadius == radius && cachedForDownscale == downscale &&
                cachedForWallpaperId == wallpaperId && cachedForTint == tintAlpha
            ) return it
        }

        if (w <= 0 || h <= 0) return null

        // Live wallpapers have no static bitmap to sample from - skip gracefully
        // rather than showing a stale/wrong texture.
        if (wm.wallpaperInfo != null) {
            return null
        }

        if (!building) {
            building = true
            blurExecutor.execute {
                val result = try {
                    buildBlurredWallpaper(context, w, h, radius, downscale, tintAlpha)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: blur build failed: $t")
                    null
                }
                mainHandler.post {
                    building = false
                    if (result != null) {
                        cachedBlurred = result
                        cachedForWidth = w
                        cachedForHeight = h
                        cachedForRadius = radius
                        cachedForDownscale = downscale
                        cachedForWallpaperId = wallpaperId
                        cachedForTint = tintAlpha
                        reapplyToAllKnownViews()
                    }
                }
            }
        }
        return null
    }

    private fun buildBlurredWallpaper(
        context: Context, w: Int, h: Int, radius: Int, downscale: Int, tintAlpha: Int
    ): Bitmap? {
        val full = loadWallpaperBitmap(context, w, h) ?: return null

        val smallW = (w / downscale).coerceAtLeast(1)
        val smallH = (h / downscale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, smallW, smallH, true)
        full.recycle()

        boxBlur(small, radius, BLUR_PASSES)

        // Real frosted-glass materials always bake in a translucent white
        // layer on top of the blur - without this, sampled text is only as
        // visible as the underlying content's contrast (e.g. near-invisible
        // against a plain sky). This is what actually makes it read as glass.
        if (tintAlpha > 0) {
            Canvas(small).drawColor(Color.argb(tintAlpha, 255, 255, 255))
        }

        val result = Bitmap.createScaledBitmap(small, w, h, true)
        if (result !== small) small.recycle()
        return result
    }

    /** Prefers the dedicated lockscreen wallpaper (FLAG_LOCK); falls back to
     *  the home wallpaper file, then to WallpaperManager.drawable. */
    private fun loadWallpaperBitmap(context: Context, w: Int, h: Int): Bitmap? {
        val wm = WallpaperManager.getInstance(context)

        for (flag in intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM)) {
            try {
                val pfd = wm.getWallpaperFile(flag) ?: continue
                pfd.use { descriptor ->
                    val decoded = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                    if (decoded != null) {
                        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(full)
                        val src = Rect(0, 0, decoded.width, decoded.height)
                        val dst = Rect(0, 0, w, h)
                        canvas.drawBitmap(decoded, src, dst, null)
                        decoded.recycle()
                        return full
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: getWallpaperFile(flag=$flag) failed: $t")
            }
        }

        return try {
            val drawable = wm.drawable ?: return null
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            full
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not read wallpaper drawable: $t")
            null
        }
    }

    // ---- Simple separable box blur, applied in-place. 3 passes ~= Gaussian blur. ----
    // Edge handling is clamp-to-edge (nearest valid pixel repeated past the
    // border). This is a standard, intentional approximation, not a bug - it
    // very slightly under-blurs right at the literal screen edge, which is
    // cosmetically negligible after downscale+upscale and rarely where clock
    // digits actually sit.

    private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        repeat(passes) {
            boxBlurHorizontal(pixels, w, h, radius)
            boxBlurVertical(pixels, w, h, radius)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val windowSize = radius * 2 + 1
        val temp = IntArray(w)
        for (y in 0 until h) {
            val rowStart = y * w
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val xi = i.coerceIn(0, w - 1)
                val p = pixels[rowStart + xi]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                temp[x] = ((sumA / windowSize) shl 24) or
                    ((sumR / windowSize) shl 16) or
                    ((sumG / windowSize) shl 8) or
                    (sumB / windowSize)

                val xAdd = (x + radius + 1).coerceIn(0, w - 1)
                val xRemove = (x - radius).coerceIn(0, w - 1)
                val pAdd = pixels[rowStart + xAdd]
                val pRemove = pixels[rowStart + xRemove]
                sumA += ((pAdd ushr 24) and 0xFF) - ((pRemove ushr 24) and 0xFF)
                sumR += ((pAdd ushr 16) and 0xFF) - ((pRemove ushr 16) and 0xFF)
                sumG += ((pAdd ushr 8) and 0xFF) - ((pRemove ushr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
            System.arraycopy(temp, 0, pixels, rowStart, w)
        }
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val windowSize = radius * 2 + 1
        val temp = IntArray(h)
        for (x in 0 until w) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (j in -radius..radius) {
                val yj = j.coerceIn(0, h - 1)
                val p = pixels[yj * w + x]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                temp[y] = ((sumA / windowSize) shl 24) or
                    ((sumR / windowSize) shl 16) or
                    ((sumG / windowSize) shl 8) or
                    (sumB / windowSize)

                val yAdd = (y + radius + 1).coerceIn(0, h - 1)
                val yRemove = (y - radius).coerceIn(0, h - 1)
                val pAdd = pixels[yAdd * w + x]
                val pRemove = pixels[yRemove * w + x]
                sumA += ((pAdd ushr 24) and 0xFF) - ((pRemove ushr 24) and 0xFF)
                sumR += ((pAdd ushr 16) and 0xFF) - ((pRemove ushr 16) and 0xFF)
                sumG += ((pAdd ushr 8) and 0xFF) - ((pRemove ushr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
            for (y in 0 until h) {
                pixels[y * w + x] = temp[y]
            }
        }
    }
}
