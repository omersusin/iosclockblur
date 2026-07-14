package com.omersusin.iosclockblur

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "iOS Blur Clock modulu kurulu.\n\n" +
                "1) LSPosed Yoneticisi'ni ac\n" +
                "2) Moduller sekmesinden bu modulu etkinlestir\n" +
                "3) Kapsam olarak System UI'yi sec\n" +
                "4) Telefonu yeniden baslat\n" +
                "5) Ayarlar > Kilit ekrani > Ozel Saat Stili'nden bir stil sec\n\n" +
                "Sorun olursa: adb logcat -s iOSClockBlur:*  " +
                "(veya su -c logcat -s iOSClockBlur:* )"
            textSize = 16f
            setPadding(48, 96, 48, 48)
            gravity = Gravity.START
            setTextColor(Color.BLACK)
        }
        setContentView(tv)
    }
}
