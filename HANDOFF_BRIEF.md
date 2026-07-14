# iOS Blur Clock — Teknik Brief (Opus / Fable / Mythos için)

Bu doküman, Claude Sonnet 5 ile bir Termux/mobil oturumunda üretilen bir LSPosed
modülünün tam durumunu özetliyor. Kod **hiçbir yerde derlenip test edilmedi**
(Sonnet'in ortamında Android SDK yok). Amaç: kod incelemesi, olası hataların
yakalanması, Android/Skia API varsayımlarının doğrulanması ve genel mimari
eleştirisi.

---

## 1. Amaç

Kullanıcının (Ömer) POCO F5 telefonunda, ROM'un yerleşik "Özel Saat Stili"
(kilit ekranı saati, ~85 hazır layout: ios1-19, miui, stylish1-10, sternum vb.)
sisteminde saat rakamlarını **iOS tarzı buzlu cam (frosted glass)** görünümüne
çevirmek. Yani rakamların içi düz renk/opaklık değil, arkalarındaki duvar
kağıdının bulanıklaştırılmış hali ile dolu olacak — gerçek "backdrop blur"
görünümü.

Kullanıcı ROM'un yerleşik "Özel saat opaklığı" ayarını (0-100%) denedi; bu
sadece alfa/saydamlık uyguluyor, arkası hâlâ keskin görünüyor — istenen buzlu
cam etkisi bu değil.

## 2. Cihaz ve ROM bağlamı

```
ro.build.version.release: 16
ro.build.version.sdk: 36
ro.build.display.id: BP4A.251205.006 release-keys
```

- POCO F5 (Snapdragon 7+ Gen 2), rooted, KernelSU, custom LineageOS tabanlı ROM.
- SystemUI: `/system_ext/priv-app/SystemUI/SystemUI.apk`, versionName=16.
- Kurulu KSU modülleri: `Specter, playintegrityfix, tricky_store, zygisksu,
  battery_tune, system_app_nuker, zygisk_lsposed` → **LSPosed zaten kurulu ve
  Zygisk üzerinden çalışıyor.**
- Kilit ekranı ayarlarında (settings secure/system) zaten zengin bir özel saat
  sistemi var: `lock_screen_custom_clock_style` (0-85), `lock_screen_custom_
  clock_opacity`, `lock_screen_custom_clock_size_scale`, `lock_screen_custom_
  clock_margin_top`, `lock_screen_custom_clock_face`, `lock_screen_custom_
  clock_color_mode`.
- Cihaz **tek elle** kullanılıyor (fiziksel kısıt), tüm iş akışı Termux
  üzerinden, PC yok. GitHub Actions ile APK derleniyor (yerel derleme yok).

## 3. Keşif süreci ve bulgular

SystemUI.apk root ile kopyalanıp jadx ile decompile edildi:

```bash
su -c 'cp /system_ext/priv-app/SystemUI/SystemUI.apk /sdcard/SystemUI.apk'
jadx -d ~/SystemUI_src /sdcard/SystemUI.apk   # 244 hata verdi ama çoğu sınıf çıktı
```

`lock_screen_custom_clock_*` ayarlarını okuyan tek sınıf bulundu:
**`com.android.systemui.clocks.ClockStyle`** (563 satır).

### 3.1 Sınıf yapısı (önemli alanlar)

```java
public final class ClockStyle extends RelativeLayout implements TunerService.Tunable {
    public static final int[] CLOCK_LAYOUTS = {0, R.layout.keyguard_clock_none,
        R.layout.keyguard_clock_oos, ... R.layout.keyguard_anci_clockdate_sternum,
        ... /* ~85 layout referansı */ };

    public static final String CLOCK_TEXT_OPACITY_KEY; // "lock_screen_custom_clock_opacity"
    public static final String CLOCK_STYLE_KEY;
    public static final String CLOCK_SIZE_KEY;
    public static final String CLOCK_COLOR_MODE_KEY;
    public static final String CLOCK_CUSTOM_COLOR_KEY;
    public static final String CLOCK_FRAME_MARGIN_TOP_KEY;
    public static final HashSet NO_COLOR_CLOCKS;

    public ViewGroup clockContainer;
    public int clockFrameMarginTop;
    public int clockOpacity;
    public int clockSizeScale;
    public ViewStub clockStub;
    public int clockStyle;
    public String colorMode;
    public View currentClockView;
    public int customColor;
    public final ArrayList styledTextViews;   // TÜM TextView'ler (TextClock dahil)
    public final ArrayList textClocks;        // sadece TextClock instance'ları
    public boolean isDozing;
    ...
}
```

**Önemli:** `styledTextViews` hem `TextClock`'ları HEM diğer `TextView`'leri
(tarih, cihaz adı, kullanıcı adı vb.) içeriyor — `cacheClockViews()` metodunda
bir `TextClock` bulunca hem `textClocks.add(view)` hem `styledTextViews.add
(view)` çağrılıyor. Bu yüzden "sadece tarih metnini de bulanıklaştır" gibi bir
opsiyon için `styledTextViews` filtrelenip `!is TextClock` olanlar alınmalı.

### 3.2 `applyClockAlpha()` — tam kod

```java
public final void applyClockAlpha() {
    int i;
    View view = this.currentClockView;
    if (view == null) return;
    if (this.isDozing) {
        i = this.clockOpacity <= 70 ? this.clockOpacity : 70;
    }
    view.setAlpha(i / 100.0f);
}
```

(Not: `i` dozing dışı dalda decompile'da eksik görünüyor — muhtemelen jadx
hatası, orijinal muhtemelen `else i = this.clockOpacity;` içeriyordu. Önemli
değil, bizim hook'umuzu etkilemiyor.)

### 3.3 `applyClockColors()` — tam kod

```java
public final void applyClockColors() {
    if (NO_COLOR_CLOCKS.contains(Integer.valueOf(this.clockStyle)) || this.textClocks.isEmpty()) return;
    int color = getContext().getColor(android.R.color.white);
    boolean z = this.isDozing && (colorMode == "accent" || colorMode == "custom");
    for (TextClock textClock : textClocks) {
        // orijinal typeface'i tag'e kaydet, tekrar uygula
        // orijinal text color'ı tag'e kaydet
        // rengi hesapla (resolveClockColor() veya white) ve setTextColor() ile uygula
    }
    for (TextView textView : styledTextViews) {
        if (textView !instanceof TextClock) {
            // aynı mantık, sadece renk
        }
    }
}
```

**Kritik gözlem:** Bu metod sadece `setTextColor()` çağırıyor —
`Paint.setShader()`'ı **hiç dokunmuyor**. Yani bizim shader'ımız bu metod
tekrar çalışsa bile (örn. AOD'ye girip çıkarken) silinmiyor. Bu, hook
mimarimizin can alıcı varsayımı.

### 3.4 `updateClockView()` — akış özeti

1. Eski `currentClockView`'ı parent'tan kaldırır, `textClocks`/
   `styledTextViews` listelerini temizler.
2. `CLOCK_LAYOUTS[clockStyle]` XML'ini `clockStub.inflate()` veya
   `LayoutInflater` ile şişirir → `currentClockView`.
3. `cacheClockViews(view)` — rekürsif olarak tüm `TextClock`/`TextView`'leri
   `textClocks`/`styledTextViews`'e ekler.
4. Kullanıcı profili ikonu/adı, cihaz adı gibi opsiyonel view'ları doldurur.
5. `applyClockAlpha()`, `applyClockColors()` çağrılır.
6. View henüz layout almadıysa (`getWidth() == 0`) bir
   `OnLayoutChangeListener` ekleyip `applyClockScale()`'i sonraya erteler —
   **biz de aynı paterni `OnGlobalLayoutListener` ile taklit ettik**, çünkü
   `getLocationOnScreen()` layout tamamlanmadan doğru değer vermez.

### 3.5 Blur altyapısı (kullanılmadı ama mevcut)

`BlurUtils.java` (`com.android.systemui.statusbar.BlurUtils`) bulundu —
`applyBlur(ViewRootImpl, radius, ...)`, `supportsBlursOnWindows()`,
`setBackgroundBlurRadius` vb. Bu, **pencere arkası** blur'u (bildirim paneli
gibi) için; canlı/dinamik arka plan gerektirir ve `ViewRootImpl`/
`SurfaceControl` seviyesinde çalışır. Kilit ekranı arka planı zaten sadece
duvar kağıdı olduğu için (başka hiçbir şey saatin arkasından geçmiyor), canlı
blur yerine **statik, önceden bulanıklaştırılmış duvar kağıdı anlık
görüntüsü** kullanmak yeterli ve çok daha basit/güvenli — bu yüzden
`BlurUtils`/`RenderEffect`/`CrossWindowBlur` API'lerine hiç dokunulmadı.

## 4. Mimari karar: LSPosed hook vs. smali patch

SystemUI.apk'yı doğrudan smali seviyesinde patchleyip sistemsiz (systemless)
overlay olarak mount etmek yerine **LSPosed/Xposed runtime hook** tercih
edildi çünkü:

- Zaten kurulu (`zygisk_lsposed` modülü aktif) — ekstra altyapı gerekmiyor.
- Tamamen geri alınabilir (LSPosed Yöneticisi'nden kapsamı kaldırmak yeterli,
  APK'ya dokunulmuyor).
- ROM güncellendiğinde smali patch'in yeniden üretilmesi gerekmez; hook
  runtime'da class/field/method adlarına göre bağlanıyor (yine de bunlar
  değişirse hook bulamaz, bkz. §8).
- Debug döngüsü çok daha hızlı: APK'yı değiştir → kur → LSPosed'de etkinleştir
  → reboot. Smali patch her seferinde SystemUI.apk'yı yeniden imzalayıp
  flaşlamak gerektirirdi.

## 5. Teknik yaklaşım: frosted-glass shader tekniği

Her `TextClock` (ve opsiyonel olarak diğer `TextView`'ler) için:

1. `WallpaperManager.getInstance(context).drawable` ile duvar kağıdı alınır
   (SystemUI process'i içinde çalıştığımız için SystemUI'nin kendi wallpaper
   erişim izinleri geçerli — ekstra permission gerekmedi).
2. Ekran boyutuna (`w x h`) render edilir, `downscale` faktörüyle (varsayılan
   6x) küçültülür.
3. 3 geçişli separable box blur uygulanır (bkz. §7.2 — Gaussian'a merkezi
   limit teoremi gereği yakınsar, tek geçişli daha karmaşık "stack blur"
   yerine bilinçli olarak tercih edildi çünkü doğruluğu elle doğrulaması çok
   daha kolay).
4. Tekrar ekran boyutuna büyütülür → tam ekran bulanık duvar kağıdı bitmap'i
   (cache'lenir, anahtar: `w, h, radius, downscale`).
5. Her `TextClock` için `view.getLocationOnScreen()` alınır, bu bitmap'ten bir
   `BitmapShader` oluşturulup `Matrix.setTranslate(-locX, -locY)` ile
   **o view'in ekrandaki gerçek konumuna hizalanır**, `TextView.getPaint().
   shader`'a atanır.

### 5.1 Matrix yönü — DOĞRULANMASI İSTENEN NOKTA

```kotlin
val matrix = Matrix()
matrix.setTranslate(-loc[0].toFloat(), -loc[1].toFloat())
shader.setLocalMatrix(matrix)
```

Akıl yürütme: `Shader.setLocalMatrix(M)` semantiği, "M shader/bitmap
uzayındaki bir noktayı canvas/hedef uzayına taşır" şeklinde varsayıldı (yani
`canvasPoint = M(bitmapPoint)`), bu yüzden View-local canvas noktası `p`'nin
tam ekran bitmap'inde karşılığı `p + loc` olması için `M = translate(-loc)`
seçildi. **Bu varsayım cihazda test edilmedi.** Eğer yön ters çıkarsa (resim
kayık/yanlış hizalı görünürse) düzeltme tek satır:
`matrix.setTranslate(loc[0].toFloat(), loc[1].toFloat())` (işareti çevir).

## 6. Modül yapısı

```
iosclockblur/
├── build.gradle                    (proje kökü, plugin versiyonları)
├── settings.gradle                 (merkezi repository tanımları)
├── gradle.properties
├── app/
│   ├── build.gradle                (compileSdk 36, minSdk 28, targetSdk 36,
│   │                                 compileOnly de.robv.android.xposed:api:82)
│   └── src/main/
│       ├── AndroidManifest.xml     (xposedmodule meta-data'ları + MainActivity)
│       ├── assets/xposed_init      (giriş sınıfı: ClockBlurHook)
│       └── java/com/omersusin/iosclockblur/
│           ├── ClockBlurHook.kt    (asıl hook, SystemUI process'inde çalışır)
│           ├── MainActivity.kt     (ayarlar ekranı, modülün kendi process'i)
│           └── Prefs.kt            (paylaşılan sabitler: key adları, default'lar)
├── .github/workflows/build.yml     (gradle/actions/setup-gradle, wrapper JAR
│                                     gerektirmiyor — cihazda internet kısıtlı
│                                     olduğu için wrapper binary indirilemedi)
└── README.md
```

## 7. Tam kaynak kod

### 7.1 `Prefs.kt`

```kotlin
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

    const val ACTION_SETTINGS_CHANGED = "com.omersusin.iosclockblur.SETTINGS_CHANGED"
}
```

### 7.2 `ClockBlurHook.kt` (SystemUI process'inde çalışan taraf)

Ana akış (`handleLoadPackage` → sadece `com.android.systemui` paketinde
çalışır):

1. `XposedHelpers.findClassIfExists("com.android.systemui.clocks.ClockStyle",
   lpparam.classLoader)` ile sınıfı bulur; bulunamazsa log basıp sessizce
   çıkar (crash yok).
2. `XposedBridge.hookAllMethods(clockStyleClass, "updateClockView", ...)` —
   `afterHookedMethod` içinde `onClockRebuilt(thisObject as View)` çağrılır.
3. `onClockRebuilt`: view'i `activeClockViews` (WeakReference listesi) içine
   kaydeder (ayarlar değişince anında yeniden uygulamak için), sonra
   layout hazırsa direkt `applyGlassEffect`, değilse `OnGlobalLayoutListener`
   ile erteler.
4. `applyGlassEffect`:
   - `XSharedPreferences(Prefs.PACKAGE_NAME, Prefs.FILE_NAME).apply { reload() }`
     ile ayarları okur (okunamazsa `Prefs.DEFAULT_*` kullanılır, crash olmaz).
   - `enabled=false` ise mevcut shader'ları temizler (`paint.shader = null`,
     `LAYER_TYPE_NONE`) ve çıkar — anlık geri alınabilir kill switch.
   - `XposedHelpers.getObjectField(view, "textClocks")` ve `"styledTextViews"`
     ile reflection üzerinden alanlara erişir.
   - `getOrBuildBlurredWallpaper(...)` çağırıp blur'lu bitmap'i alır/üretir.
   - `paintShaderOnto(textClocks, blurred)` — her view için shader hesaplanıp
     basılır (bkz. §5.1).
   - `includeDate=true` ise `styledTextViews.filter { it !is TextClock }`
     üzerinde de aynısı uygulanır.
5. `registerReceiversOnce`: iki `BroadcastReceiver` kaydeder —
   `ACTION_WALLPAPER_CHANGED` (cache temizler) ve `Prefs.
   ACTION_SETTINGS_CHANGED` (ayarlar ekranından gönderilir, cache temizler +
   `activeClockViews`'daki tüm view'lara yeniden uygular). Android 13+
   (`Build.VERSION.SDK_INT >= 33`) için `Context.RECEIVER_EXPORTED` flag'i
   zorunlu — bu satır olmadan `registerReceiver` çağrısı bu cihazda
   (SDK 36) muhtemelen `IllegalArgumentException` atardı.
6. `getOrBuildBlurredWallpaper`: cache anahtarı `(w, h, radius, downscale)`
   dörtlüsü; eşleşmezse yeniden üretir.
7. `boxBlur`/`boxBlurHorizontal`/`boxBlurVertical`: klasik sliding-window
   separable box blur, ARGB kanalları ayrı ayrı toplanıp kaydırılıyor. O(w*h)
   karmaşıklık, radius'tan bağımsız (sliding window sayesinde).

### 7.3 `MainActivity.kt` (modülün kendi process'i — ayarlar ekranı)

- XML layout kullanılmadı, tüm UI programatik (`LinearLayout` + `ScrollView`
  + `Switch` + `SeekBar` + `TextView`) — ekstra resource dosyası riskini
  azaltmak için bilinçli tercih.
- `onCreate`: prefs boşsa default değerleri yazar (dosyanın diskte var
  olmasını garantilemek için — `getSharedPreferences()` tek başına dosya
  oluşturmayabilir).
- `makeWorldReadable()`: `dataDir`, `dataDir/shared_prefs`, ve prefs XML
  dosyasının kendisi üzerinde `setReadable(true, false)` /
  `setExecutable(true, false)` çağırır — SystemUI (farklı UID) dosyayı
  okuyabilsin diye. **Bu, on yıllardır kullanılan standart Xposed ayarları
  paylaşma yöntemi ama bazı SELinux politikalı ROM'larda engellenebilir**
  (bkz. §8).
- Her ayar değişikliğinde: `prefs.edit()...apply()` → `makeWorldReadable()`
  tekrar → `sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage
  ("com.android.systemui"))`.

## 8. Bilinen riskler / doğrulanmamış varsayımlar

Öncelik sırasına göre:

1. **XSharedPreferences + SELinux.** En riskli nokta. Unix dosya izinleri
   doğru ayarlansa bile, ROM'un SELinux politikası SystemUI domain'inin
   üçüncü parti bir app'in `/data/data/<pkg>/` altına erişimini
   engelliyor olabilir. Test edilmeden bilinemez. Alternatif: root
   üzerinden `/data/local/tmp/` gibi dünyaya açık bir konuma dosya yazmak
   (kullanıcı zaten root'lu, Termux + `su` ile kolayca yapılabilir) — daha
   garanti ama modülün kendi Activity'sinden `su` çağırmak gerekir
   (örn. `libsu` bağımlılığı ya da manuel `Runtime.exec("su")`).

2. **`Matrix.setLocalMatrix` yönü** (§5.1) — Skia semantiğine dair akıl
   yürütmeyle karar verildi, ampirik doğrulama yok. Yanlışsa görsel olarak
   hemen belli olur (resim kayık görünür), tek satır işaret değişikliğiyle
   düzelir.

3. **Reflection alan adları** (`textClocks`, `styledTextViews`) — bu ROM
   build'inin (BP4A.251205.006) decompile çıktısından alındı, isimler
   deobfuscate/anlamlı görünüyor (muhtemelen minifyEnabled=false ile
   derlenmiş). ROM güncellenirse bu isimler değişebilir; hook o zaman
   sessizce hiçbir şey yapmaz (log'da "ClockStyle class not found" ya da
   `getObjectField` exception'ı görülür, crash olmaz).

4. **`LAYER_TYPE_SOFTWARE` zorlaması** — shader + donanım hızlandırmalı metin
   çiziminde bazı GPU sürücülerinde kırpma/görsel bozukluk riskine karşı
   eklendi (yorum satırında belirtildi). Performans etkisi ölçülmedi;
   `TextClock` her saniye/dakika invalidate olduğu için sürekli software
   layer render'ı pil/performans açısından gözlemlenmeli.

5. **Rotasyon** — cache anahtarı `w`/`h` değişince otomatik yenileniyor
   (teorik olarak doğru) ama gerçek cihazda test edilmedi.

6. **AOD/dozing geçişleri** — `applyClockColors()`'ın shader'ı silmediği
   varsayımına dayanıyor (§3.3'te doğrulandı, sadece `setTextColor`
   çağırıyor) ama `isDozing` geçişinde `updateClockView()` dışında başka bir
   yol tetiklenip tetiklenmediği (örn. view'in tamamen yeniden inflate
   edilip edilmediği) tam doğrulanmadı.

## 9. Build/CI pipeline

`.github/workflows/build.yml` — `gradle/actions/setup-gradle@v4` ile
Gradle 8.7 kurulup `gradle assembleDebug` çalıştırılıyor (Gradle wrapper JAR
binary'si Sonnet'in ağ kısıtlı ortamında indirilemediği için wrapper hiç
eklenmedi, doğrudan kurulu gradle kullanılıyor). AGP 8.5.2, Kotlin 1.9.24,
compileSdk/targetSdk 36, minSdk 28. `de.robv.android.xposed:api:82`
`compileOnly` bağımlılığı, `https://api.xposed.info/` maven repo'sundan.

## 10. Opus/Fable/Mythos'tan istenen yardım

- Yukarıdaki §8'deki 6 riskin her biri için: mümkünse Android/Skia
  dokümantasyonuna veya bilinen açık kaynak örneklerine dayanarak
  varsayımları doğrula/çürüt.
- Kod incelemesi: özellikle `ClockBlurHook.kt`'deki reflection kullanımı,
  null-safety, ve `WeakReference` yönetiminde (memory leak, çifte
  kayıt vb.) gözden kaçan bir şey var mı?
- `boxBlur` algoritmasının doğruluğunu (sliding window matematiği) elle
  doğrulamak — küçük bir örnek üzerinde iz sürülebilir.
- Genel mimari eleştiri: bu problem için daha sağlam/daha az riskli başka
  bir yaklaşım var mıydı (örn. RenderEffect tabanlı, ViewOverlay tabanlı,
  ya da SystemUI'nin kendi `BlurUtils`'ini farklı şekilde kullanan bir
  yöntem)?
- İlk derleme sonrası çıkacak Gradle/Kotlin hatalarını önceden tahmin edip
  işaretlemek faydalı olur (örn. AGP 8.5.2 + compileSdk 36 kombinasyonunun
  bu tarihte gerçekten var olup olmadığı Sonnet'in web'de doğrulayamadığı
  bir nokta).
