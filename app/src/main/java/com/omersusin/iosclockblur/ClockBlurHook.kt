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
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Makes the ROM's built-in lockscreen "Custom Clock Style" (ClockStyle.java, the
 * "Ozel Saat Stili" / Sternum-ios-etc layouts) render with a real frosted-glass look:
 * each TextClock's Paint gets a BitmapShader built from a blurred, screen-aligned
 * crop of the current wallpaper, instead of a flat color. Because the lockscreen
 * background genuinely IS the wallpaper (nothing else draws behind it), a static
 * blurred snapshot lines up with reality - no live backdrop blur needed.
 *
 * Hook point: ClockStyle.updateClockView() (afterHookedMethod). This method is
 * called on first inflate and every time the user changes clock style/size in
 * Settings, and it already runs applyClockAlpha()/applyClockColors() internally
 * before returning - so hooking "after" lets our shader win over whatever color
 * those set (setTextColor() only touches Paint.color, never clears Paint.shader).
 */
class ClockBlurHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "iOSClockBlur"
        private const val TARGET_PKG = "com.android.systemui"
        private const val CLOCK_CLASS = "com.android.systemui.clocks.ClockStyle"

        // Downscale factor before blurring (smaller = faster + softer blur).
        private const val DOWNSCALE = 6
        // Box-blur radius applied to the downscaled bitmap.
        private const val BLUR_RADIUS = 10
        // 3 box-blur passes approximates a Gaussian blur closely enough for this use.
        private const val BLUR_PASSES = 3
    }

    private var cachedBlurred: Bitmap? = null
    private var cachedForWidth = 0
    private var cachedForHeight = 0
    private var receiverRegistered = false

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

    private fun applyGlassEffect(clockStyleView: View) {
        val context = clockStyleView.context ?: return
        ensureWallpaperChangeListener(context)

        @Suppress("UNCHECKED_CAST")
        val textClocks = XposedHelpers.getObjectField(clockStyleView, "textClocks") as? ArrayList<View>
        if (textClocks.isNullOrEmpty()) return

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val blurred = getOrBuildBlurredWallpaper(context, screenW, screenH) ?: return

        val loc = IntArray(2)
        var applied = 0
        for (v in textClocks) {
            val tc = v as? TextClock ?: continue
            tc.getLocationOnScreen(loc)

            val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            matrix.setTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            shader.setLocalMatrix(matrix)

            tc.paint.shader = shader
            // Software layer needed: HW-accelerated text + shader can clip oddly
            // on some GPU drivers when the shader's local matrix uses large offsets.
            tc.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            tc.invalidate()
            applied++
        }
        if (applied > 0) {
            XposedBridge.log("$TAG: glass shader applied to $applied TextClock view(s)")
        }
    }

    private fun ensureWallpaperChangeListener(context: Context) {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            context.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    cachedBlurred = null
                    XposedBridge.log("$TAG: wallpaper changed, cache invalidated")
                }
            }, filter)
            receiverRegistered = true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: could not register wallpaper-change receiver: $t")
        }
    }

    private fun getOrBuildBlurredWallpaper(context: Context, w: Int, h: Int): Bitmap? {
        cachedBlurred?.let { if (cachedForWidth == w && cachedForHeight == h) return it }
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

        val smallW = (w / DOWNSCALE).coerceAtLeast(1)
        val smallH = (h / DOWNSCALE).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, smallW, smallH, true)
        full.recycle()

        boxBlur(small, BLUR_RADIUS, BLUR_PASSES)

        val result = Bitmap.createScaledBitmap(small, w, h, true)
        if (result !== small) small.recycle()

        cachedBlurred = result
        cachedForWidth = w
        cachedForHeight = h
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
