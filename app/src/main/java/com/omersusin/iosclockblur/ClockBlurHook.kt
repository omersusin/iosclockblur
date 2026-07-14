package com.omersusin.iosclockblur

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextClock
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Makes the ROM's built-in lockscreen "Custom Clock Style" (ClockStyle.java, the
 * "Ozel Saat Stili" / Sternum-ios-etc layouts) render with a real frosted-glass
 * look: each TextClock's Paint gets a BitmapShader built from a blurred,
 * screen-aligned crop of the current wallpaper, instead of a flat color.
 *
 * Settings (enabled, blur radius, downscale, include-date) are written by
 * MainActivity into normal SharedPreferences and read here via
 * XSharedPreferences, since this code executes inside SystemUI's process
 * (different UID) after LSPosed injects it - it can't use the module's own
 * getSharedPreferences() directly.
 */
class ClockBlurHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "iOSClockBlur"
        private const val TARGET_PKG = "com.android.systemui"
        private const val CLOCK_CLASS = "com.android.systemui.clocks.ClockStyle"
        private const val BLUR_PASSES = 3
    }

    private var cachedBlurred: Bitmap? = null
    private var cachedForWidth = 0
    private var cachedForHeight = 0
    private var cachedForRadius = -1
    private var cachedForDownscale = -1

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

        XposedBridge.log("$TAG: hooked $CLOCK_CLASS.updateClockView successfully")
    }

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

    private fun rememberView(view: View) {
        activeClockViews.removeAll { it.get() == null || it.get() === view }
        activeClockViews.add(WeakReference(view))
    }

    private fun applyGlassEffect(clockStyleView: View) {
        val context = clockStyleView.context ?: return

        val xprefs = try {
            XSharedPreferences(Prefs.PACKAGE_NAME, Prefs.FILE_NAME).apply { reload() }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not read settings, using defaults: $t")
            null
        }

        val enabled = xprefs?.getBoolean(Prefs.KEY_ENABLED, Prefs.DEFAULT_ENABLED) ?: Prefs.DEFAULT_ENABLED
        val radius = xprefs?.getInt(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS) ?: Prefs.DEFAULT_BLUR_RADIUS
        val downscale = xprefs?.getInt(Prefs.KEY_DOWNSCALE, Prefs.DEFAULT_DOWNSCALE) ?: Prefs.DEFAULT_DOWNSCALE
        val includeDate = xprefs?.getBoolean(Prefs.KEY_INCLUDE_DATE, Prefs.DEFAULT_INCLUDE_DATE) ?: Prefs.DEFAULT_INCLUDE_DATE

        @Suppress("UNCHECKED_CAST")
        val textClocks = XposedHelpers.getObjectField(clockStyleView, "textClocks") as? ArrayList<View>
        @Suppress("UNCHECKED_CAST")
        val styledTextViews = XposedHelpers.getObjectField(clockStyleView, "styledTextViews") as? ArrayList<View>

        if (!enabled) {
            clearShaders(textClocks)
            clearShaders(styledTextViews)
            return
        }

        if (textClocks.isNullOrEmpty()) return

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val blurred = getOrBuildBlurredWallpaper(context, screenW, screenH, radius, downscale) ?: return

        var applied = paintShaderOnto(textClocks, blurred)
        if (includeDate && styledTextViews != null) {
            val dateViews = styledTextViews.filter { it !is TextClock }
            applied += paintShaderOnto(dateViews, blurred)
        }
        if (applied > 0) {
            XposedBridge.log("$TAG: glass shader applied to $applied view(s)")
        }
    }

    private fun paintShaderOnto(views: List<View>, blurred: Bitmap): Int {
        val loc = IntArray(2)
        var count = 0
        for (v in views) {
            val tv = v as? TextView ?: continue
            tv.getLocationOnScreen(loc)
            val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            matrix.setTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            shader.setLocalMatrix(matrix)
            tv.paint.shader = shader
            tv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            tv.invalidate()
            count++
        }
        return count
    }

    private fun clearShaders(views: List<View>?) {
        if (views == null) return
        for (v in views) {
            val tv = v as? TextView ?: continue
            if (tv.paint.shader != null) {
                tv.paint.shader = null
                tv.setLayerType(View.LAYER_TYPE_NONE, null)
                tv.invalidate()
            }
        }
    }

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

    private fun getOrBuildBlurredWallpaper(
        context: Context, w: Int, h: Int, radius: Int, downscale: Int
    ): Bitmap? {
        cachedBlurred?.let {
            if (cachedForWidth == w && cachedForHeight == h &&
                cachedForRadius == radius && cachedForDownscale == downscale
            ) return it
        }
        if (w <= 0 || h <= 0) return null

        val drawable = try {
            WallpaperManager.getInstance(context).drawable
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not read wallpaper: $t")
            null
        } ?: return null

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)

        val smallW = (w / downscale).coerceAtLeast(1)
        val smallH = (h / downscale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, smallW, smallH, true)
        full.recycle()

        boxBlur(small, radius, BLUR_PASSES)

        val result = Bitmap.createScaledBitmap(small, w, h, true)
        if (result !== small) small.recycle()

        cachedBlurred = result
        cachedForWidth = w
        cachedForHeight = h
        cachedForRadius = radius
        cachedForDownscale = downscale
        return result
    }

    // ---- Simple separable box blur, applied in-place. 3 passes ~= Gaussian blur. ----

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
