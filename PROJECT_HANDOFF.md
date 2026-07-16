# iOS Blur Clock — Tam Proje Dokümantasyonu (Handoff)

**Bu belge, projeyi hiç bilmeyen ama daha güçlü/gelişmiş bir AI'ya (Opus/
Fable/Mythos vb.) verilmek üzere yazıldı.** Amaç: mimariyi, alınan kararları,
denenip reddedilen yolları ve bulunan/gerçekten düzeltilen hataları eksiksiz
aktarmak — böylece o AI sıfırdan keşfetmek yerine kaldığımız yerden devam
edebilsin.

**Not (bu belgeyi okuyan AI'ya):** Kod dosyaları gerçek dosya olarak da
verilecek (zip). Eğer sen dosya/zip üretemiyorsan, ürettiğin kodu her dosya
için ayrı, yol adı açıkça belirtilmiş markdown code block'ları halinde yaz —
kullanıcı bunları Termux'ta elle kopyalayıp yapıştıracak.

---

## 1. Bağlam: kullanıcı ve ortam

- Kullanıcı: Ömer, Cizre/Türkiye merkezli bağımsız Android geliştirici.
- Cihaz: **POCO F5** (Snapdragon 7+ Gen 2), rooted, **KernelSU**, custom
  LineageOS tabanlı ROM. `ro.build.version.release=16`, `sdk=36`,
  `ro.build.display.id=BP4A.251205.006 release-keys`.
- **Fiziksel kısıt: tek elle kullanım.** Bu tüm iş akışını şekillendiriyor.
- **PC yok.** Her şey Termux üzerinden, mobil, tek elle yapılıyor.
- İş akışı: Claude (ya da başka bir üst AI) spec/kod yazar → kullanıcı
  Termux'ta çalıştırır/git push eder → GitHub Actions APK derler → kullanıcı
  indirip kurar.
- Kurulu KSU modülleri: `Specter, playintegrityfix, tricky_store, zygisksu,
  battery_tune, system_app_nuker, zygisk_lsposed` — yani **LSPosed zaten
  Zygisk üzerinden aktif**.
- Kullanıcının **daha önce kendi başına ürettiği, alışık olduğu** proje
  paterni: KernelSU modülleri + **WebUI** (`webroot/index.html` giriş
  noktalı, KernelSU Next'in Promise tabanlı JS API'si `await ksu.exec(cmd)`
  ile) — örnek: "Smart Cache Cleaner" (v1-v5, WebUI dashboard, konfigüre
  edilebilir aralıklar, whitelist, çok kullanıcı desteği), "SystemSweep"
  (5 sekmeli sistem temizleyici, planlandı). **Bu belgenin sonunda bu paterne
  geçiş talebi var, bkz. §11.**

## 2. Amaç

ROM'un yerleşik "Özel Saat Stili" (kilit ekranı saati, ~85 hazır layout:
ios1-19, miui, stylish1-10, sternum vb.) sisteminde saat rakamlarını **iOS
tarzı buzlu cam (frosted glass)** görünümüne çevirmek: rakamların içi düz
renk/opaklık değil, arkalarındaki duvar kağıdının bulanıklaştırılmış +
üzerine hafif beyaz katman eklenmiş hali ile dolu.

ROM'un yerleşik "Özel saat opaklığı" ayarı (0-100%) denendi; bu sadece
alfa/saydamlık uyguluyor, arkası hâlâ keskin görünüyor — istenen buzlu cam
etkisi bu değil.

**SONUÇ (bu belgenin yazıldığı an itibariyle): hedef görsel olarak
BAŞARILDI.** Kullanıcı gerçek bir iOS ekran görüntüsüyle yan yana koyup
karşılaştırdı, sonuç görsel olarak çok yakın (bkz. §6.5).

## 3. Keşif: ClockStyle.java analizi

SystemUI.apk root ile kopyalanıp jadx ile decompile edildi. ROM'un özel saat
sistemini yöneten sınıf: **`com.android.systemui.clocks.ClockStyle`**
(`RelativeLayout` alt sınıfı, `TunerService.Tunable` implement ediyor, 563
satır).

### 3.1 Önemli alanlar (reflection ile erişilenler)

```java
public final class ClockStyle extends RelativeLayout implements TunerService.Tunable {
    public static final int[] CLOCK_LAYOUTS = {0, R.layout.keyguard_clock_none, ...}; // ~85 layout
    public int clockOpacity;
    public int clockStyle;
    public View currentClockView;
    public final ArrayList styledTextViews;   // TÜM TextView'ler (TextClock DAHIL)
    public final ArrayList textClocks;        // sadece TextClock instance'ları
    public boolean isDozing;
    ...
}
```

`cacheClockViews()` bir `TextClock` bulunca **hem** `textClocks.add(view)`
**hem** `styledTextViews.add(view)` çağırıyor — yani `styledTextViews`,
`textClocks`'un üst kümesi.

### 3.2 `applyClockAlpha()` — sadece `View.setAlpha()` çağırıyor, bizim
shader'ımıza dokunmuyor (Paint.shader ile Paint.alpha/color bağımsız
kavramlar, Skia'da shader varsa rengin üzerine geçer).

### 3.3 `applyClockColors()` — sadece `TextView.setTextColor()` çağırıyor.
**Kritik gözlem:** `setTextColor()` `Paint.shader`'ı asla temizlemiyor —
bizim hook mimarimizin can alıcı varsayımı buydu ve doğru çıktı.

### 3.4 `updateClockView()` akışı: eski view'i kaldır → `CLOCK_LAYOUTS[style]`
inflate et → `cacheClockViews()` ile `textClocks`/`styledTextViews`'i
doldur → `applyClockAlpha()` + `applyClockColors()` çağır → layout
tamamlanmadıysa (`getWidth()==0`) `OnLayoutChangeListener` ile
`applyClockScale()`'i ertele.

### 3.5 Blur altyapısı (kullanılmadı, bilinçli karar)

`com.android.systemui.statusbar.BlurUtils` bulundu — `applyBlur
(ViewRootImpl, radius, ...)`, cross-window blur API'leri. Bu, **pencere
arkasını** bulanıklaştırıyor (bildirim paneli gibi), canlı/dinamik arka plan
gerektiriyor. Kilit ekranı arka planı zaten sadece duvar kağıdı olduğu için
(başka hiçbir şey saatin arkasından geçmiyor), **statik, önceden
bulanıklaştırılmış duvar kağıdı anlık görüntüsü** kullanmak yeterli ve çok
daha basit/güvenli — bu yüzden `BlurUtils`/`RenderEffect`/`CrossWindowBlur`
API'lerine hiç dokunulmadı. Bu karar test edildi ve doğru çıktı.

## 4. Mimari karar: LSPosed hook (smali patch değil)

SystemUI.apk'yı doğrudan smali seviyesinde patchlemek yerine **LSPosed
runtime hook** tercih edildi: zaten kurulu, tamamen geri alınabilir, ROM
güncellemesinde yeniden üretilmesi gerekmiyor, debug döngüsü çok daha hızlı.
Bu karar doğru çıktı — proje boyunca hiç smali'ye dönme ihtiyacı olmadı.

## 5. Teknik yaklaşım: frosted-glass shader tekniği

Her `TextClock` için:

1. `WallpaperManager` üzerinden duvar kağıdı alınır — önce **kilit ekranı
   duvar kağıdı** (`FLAG_LOCK`), yoksa `FLAG_SYSTEM`, en son `.drawable`
   fallback zinciri.
2. Ekran boyutuna render edilir, `downscale` faktörüyle küçültülür.
3. **3 geçişli separable box blur** uygulanır (sliding-window, O(w×h),
   radius'tan bağımsız — klasik algoritma, Gaussian'a merkezi limit
   teoremi gereği yakınsıyor).
4. **Yarı saydam beyaz katman** (`Canvas.drawColor(Color.argb(tintAlpha,
   255,255,255))`) — bu adım **sonradan eklendi ve kritik çıktı**, bkz.
   §7.3.
5. Tekrar ekran boyutuna büyütülür → `BitmapShader` yapılır.
6. Her `TextClock` için `view.getLocationOnScreen()` alınıp
   `Matrix.setTranslate(-locX, -locY)` ile **o view'in ekrandaki gerçek
   konumuna hizalanır**, `TextView.getPaint().shader`'a atanır.

### 5.1 Matrix yönü — DOĞRULANDI (artık spekülasyon değil)

```kotlin
val matrix = Matrix()
matrix.setTranslate(-loc[0].toFloat(), -loc[1].toFloat())
shader.setLocalMatrix(matrix)
```

Bu satırın doğruluğu ilk yazıldığında sadece Skia semantiği akıl
yürütmesiyle savunulmuştu (test edilmemişti). **Artık gerçek cihazda görsel
olarak doğrulandı** — kullanıcı ekran görüntülerinde rakamların içindeki
doku (ay, ağaç dalları) arkadaki gerçek konumla doğru hizalı görünüyor,
kaymış/ters değil. Bu konuda ekstra doğrulamaya gerek yok.

## 6. Ayarlar sistemi

### 6.1 Mevcut mimari (henüz değiştirilmedi — §11'de değişiklik isteniyor)

Ayarlar iki kaynaktan okunuyor (sırasıyla denenir):

1. **Root-yazılan JSON** (`/data/local/tmp/iosclockblur/config.json`) —
   `MainActivity` (normal bir Android Activity) `Runtime.exec(arrayOf("su",
   "-c", ...))` ile bu dosyayı yazmaya çalışıyor. Kullanıcının bu app'e
   **ayrıca** root vermesi gerekiyor (KernelSU prompt).
2. **XSharedPreferences fallback** — `MainActivity` ayrıca normal
   `SharedPreferences`'a da yazıyor, dosya izinlerini `setReadable(true,
   false)` ile "world-readable" yapmaya çalışıyor. SystemUI process'i
   (farklı UID) bunu `XSharedPreferences` ile okuyor. **Bilinen risk:**
   SELinux, sıkı politikalı ROM'larda bunu engelleyebilir.

Ayar değişince `MainActivity`, `com.android.systemui`'ye token'lı bir
broadcast (`ACTION_SETTINGS_CHANGED`) gönderiyor; hook bunu dinleyip cache'i
temizleyip `activeClockViews` (WeakReference listesi) üzerinden anında
yeniden boyuyor.

### 6.2 Ayarlanabilir değerler

| Ayar | Varsayılan (güncel) | Aralık |
|---|---|---|
| Etkin | true | - |
| Blur şiddeti | **12** | 2-24 |
| Küçültme oranı | **6** | 3-12 |
| Parlaklık/beyazlık katmanı | **170** | 0-220 |
| Tarihi de bulanıklaştır | false | - |
| Uyumluluk modu (force software layer) | false | - |

**Bu üç değer (12/6/170) kullanıcının kendi cihazında, gerçek bir iOS ekran
görüntüsüyle yan yana karşılaştırarak bulduğu, görsel olarak doğrulanmış en
iyi sonuç.** Varsayılan olarak koda işlendi (bu belgeyle birlikte verilen
kaynak kodda zaten güncel).

### 6.3 AOD/Doze farkındalığı

`ClockStyle.applyClockAlpha()` de ayrıca hook'landı (sadece
`updateClockView()` değil) çünkü dozing geçişleri her zaman tam view
rebuild'i tetiklemiyor. `isDozing` alanı reflection ile okunup true ise
shader tamamen kaldırılıyor — AOD arka planı siyah olduğu için (wallpaper
görünmüyor) statik buzlu-cam dokusu hem anlamsız hem gereksiz burn-in
riski.

### 6.4 Asenkron blur üretimi

Blur işlemi artık `Executors.newSingleThreadExecutor()` üzerinde arka planda
üretiliyor, sonuç `Handler(Looper.getMainLooper())` ile ana thread'e
dönüyor. Bu, doze geçişleri sıklaştıkça (ekran açma/kapama döngüleri) jank
riskini azaltmak için eklendi.

### 6.5 Görsel doğrulama (başarılı)

Kullanıcı, iOS'un gerçek bir kilit ekranı ekran görüntüsünü (aynı duvar
kağıdı — mavi/mor çiçekli ağaç, gece, dolunay) referans alıp kendi
cihazındaki sonuçla yan yana karşılaştırdı. Blur=12, Küçültme=6,
Parlaklık=170 kombinasyonunda **sonuç görsel olarak iOS'a çok yakın** —
rakamlar yarı saydam, arkadaki ay/ağaç dokusu yumuşak şekilde seçiliyor,
metin hâlâ net okunabilir. Bu, projenin görsel hedefinin karşılandığının
kanıtı.

## 7. Karşılaşılan gerçek hatalar ve düzeltmeleri

### 7.1 Root shell PATH hatası (kullanıcı hatası, kod hatası değil)

İlk diagnostic komutlarında kullanıcı önce `su` yazıp root shell'e girip
sonra `pkg install` içeren birleşik komutu yapıştırmıştı — root shell'in
PATH'i Termux'un `pkg` komutunu görmüyor. Çözüm: komutu `su -c '...'` ile
sadece gereken tek adımı root yapacak şekilde yeniden yazdık.

### 7.2 KRİTİK: Constructor'da `Handler(Looper.getMainLooper())` — modül
**hiçbir yere enjekte olamıyordu**

En ciddi ve en uzun süre teşhis edilemeyen hata buydu. `ClockBlurHook`
sınıfının alan tanımlarında (class field initializer, yani constructor'da
çalışan kod):

```kotlin
private val mainHandler = Handler(Looper.getMainLooper())
```

**Sorun:** LSPosed, `handleLoadPackage()`'ı çağırabilmek için bu sınıfı
zygote'un forkladığı **her process'te** (system_server, SystemUI, hatta
modülün kendi process'i) önce inşa ediyor — "bu paket bizim hedefimiz mi"
kontrolü `handleLoadPackage()`'ın İÇİNDE yapılıyor, yani constructor'dan
SONRA. O erken anda o process'in ana thread Looper'ı henüz hazır olmuyor
(`Looper.getMainLooper()` null dönüyor), `Handler` constructor'ı null
Looper ile NPE atıyor. **Constructor patladığı için `handleLoadPackage`
hiçbir process'te hiç çalışamadı** — LSPosed tarafında modül "doğru kurulu,
doğru kapsamda" görünüyordu ama SystemUI'ye asla enjekte olamıyordu.

**Teşhis yöntemi:** Kullanıcıdan bir LSPosed diagnostic zip'i istendi
(LSPosed Yöneticisi'nin kendi "Günlükler" ekranından indirilebiliyor).
`log/verbose_*.log` içinde şu satır bulundu:

```
NullPointerException: Attempt to read from field
'android.os.MessageQueue android.os.Looper.mQueue' on a null object
reference in method 'void android.os.Handler.<init>...'
at com.omersusin.iosclockblur.ClockBlurHook.<init>(ClockBlurHook.kt:80)
```

**Düzeltme:**
```kotlin
private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
```

`by lazy` ile Handler oluşturma, gerçekten ihtiyaç duyulduğu ana (ilk blur
üretimi tetiklendiğinde, o zaman SystemUI'nin ana thread'i zaten hazır)
ertelendi. **Bu tek satır, projenin "hiçbir şey çalışmıyor" durumundan
"çalışıyor" durumuna geçtiği dönüm noktasıydı.**

**Genel ders (başka Xposed modüllerinde de dikkat edilmeli):** Xposed/
LSPosed modüllerinde class-level field initializer'larda **Looper, Handler,
Context'e bağlı herhangi bir şey oluşturmayın** — hepsi `by lazy` ya da
gerçek kullanım anına ertelenmeli, çünkü constructor her zygote-forked
process'te çalışır, sadece hedef process'te değil.

### 7.3 Buzlu cam görünmüyordu — tint katmanı eksikliği

İlk çalışan sürümde shader doğru uygulanıyordu ama sonuç **neredeyse
görünmezdi** — özellikle düz/az kontrastlı gökyüzü bölgelerinde rakamlar
arka planla aynı renkte kayboluyordu. **Sebep:** Ham blur, arka planın
sadece bulanık bir kopyasını üretiyor; eğer arka plan zaten düşük
kontrastlıysa (düz mavi gökyüzü), bulanık hali de neredeyse aynı renk
kalıyor. Gerçek iOS/Material "buzlu cam" materyalleri **asla** ham blur
kullanmaz — her zaman üzerine yarı saydam bir beyaz/parlaklık katmanı
eklenir. Bu eksikti. §5 adım 4'teki `Canvas.drawColor(Color.argb(tintAlpha,
255,255,255))` eklenerek düzeltildi ve bu, projenin ikinci dönüm noktasıydı
(§6.5'teki başarılı sonuç bu düzeltmeden sonra geldi).

## 8. Native "Derinlik Duvar Kağıdı" keşfi (önemli bağlam)

Diagnostic script (§10) çalıştırıldığında, ROM'da **zaten yerleşik, bizim
modülden bağımsız bir "Derinlik Efekti" sistemi** olduğu ortaya çıktı:

```
depth_wallpaper_enabled=1
depth_wallpaper_subject_image_uri=/sdcard/DepthWallpaper/depthwallpaper/DEPTH_WALLPAPER_SUBJECT_*.png
```

Ayarlar'da "Kilit ekranı > Derinlik Duvar Kağıdı" bölümü bulundu — açıklama
metni birebir: **"iOS'un Duvar Kağıdı Nesne Segmentasyonu'ndan esinlenen
derinlik duvar kağıdı özelliği, bir duvar kağıdı derinlik efekti göstermek
için bir nesnenin kilit ekranı saatinin üzerine yerleştirilmesini
sağlar."** Kullanıcı bunu aktive etti; bu, konuşmanın en başındaki referans
ekran görüntüsündeki "özne saatin önünde" efektiyle birebir aynı şey, bizim
buzlu-cam modülümüzden **tamamen farklı ve bağımsız bir katman**:

- **Native derinlik efekti** = duvar kağıdının önplan öznesi (ağaç/dağ)
  saatin **önünde**, Z-sıralaması değişikliği.
- **Bizim LSPosed modülümüz** = saat rakamlarının **içi** bulanık/saydam
  doku.

İkisi teorik olarak aynı anda açık kalabilir, çakışmamaları gerekir (biri
katman sırasını, diğeri metin dolgusunu değiştiriyor). Bu henüz birlikte
test edilmedi.

## 9. Bilinçli olarak REDDEDİLEN/uygulanmayan öneriler (tekrar gündeme
getirilmemeli, gerekçeleriyle)

Proje sırasında (kullanıcının başka bir üst-AI'dan aldığı review'lar dahil)
şu öneriler değerlendirilip **bilinçli olarak reddedildi**:

- **Status bar offset "düzeltmesi"** (`getLocationOnScreen()`'den status bar
  yüksekliği çıkarma) — YANLIŞ, `getLocationOnScreen()` zaten gerçek ekran
  koordinatını dönüyor, ekstra çıkarma hizalamayı bozar. Görsel test bunu
  doğruladı (§6.5, hizalama sorunsuz).
- **Box blur kenar davranışını "kritik hata" olarak düzeltmek** — standart
  clamp-to-edge yaklaşımı, kozmetik ve saat nadiren tam ekran kenarına
  değiyor.
- **Tam "evrensel/ROM-agnostic" mimari** (TextClock.onAttachedToWindow genel
  hook'u + keyguard heuristiği + Compose/canvas overlay katmanı, farklı
  ROM'larda farklı saat render tekniklerini (TextView/custom-canvas/Compose)
  kapsayan 3 katmanlı tespit motoru) — kapsam dışı bırakıldı: tek cihaz/tek
  ROM için gereksiz risk, test edilemeyen ROM'lar için kör kod, status bar
  saati gibi yanlış TextClock'ları yakalama riski. **§10'daki diagnostic
  script tam olarak bu kararı veri ile (spekülasyon değil) yeniden
  değerlendirmek için yazıldı** — henüz başka ROM'dan veri gelmedi.
- Manuel `bitmap.recycle()` çağırma — crash riski (shader hâlâ eski
  bitmap'i tutuyorsa); GC'ye bırakmak tercih edildi.
- Ekstra kozmetik katmanlar (doygunluk/saturation boost, kontrast, metin
  gölgesi) — tint katmanı (§7.3) tek başına yeterli görsel sonucu verdiği
  için eklenmedi. İstenirse kolayca eklenebilir.

## 10. Diagnostic script (`clockdiag.sh`)

Başka ROM'larda (HyperOS, ColorOS, Samsung OneUI vb.) veri toplamak için
yazıldı. Bağımlılıksız/taşınabilir (jadx gerektirmiyor, sadece `strings` +
`unzip`, ikisi de yoksa fallback var). Topladığı bilgiler:

1. Cihaz/ROM parmak izi
2. `clock|lockscreen|keyguard|depth|wallpaper|blur|glass` içeren tüm
   Settings anahtarları (secure/system/global)
3. SystemUI APK'sının dex'lerinde `clock`/`keyguard`/`depth`/`blur` geçen
   sınıf adları (jadx yerine hızlı `strings` taraması)
4. Varsayılan launcher'ın aynı şekilde taranması (derinlik efekti genelde
   launcher'da yaşıyor, SystemUI'de değil — POCO F5'te doğrulandı, §8)
5. Root/Xposed/LSPosed durumu

POCO F5'te bir kez çalıştırıldı ve §8'deki native derinlik keşfini
sağladı. **Henüz başka bir ROM'da çalıştırılmadı** — kullanıcı bunu
HyperOS/ColorOS cihazlarında da çalıştırıp sonuçları toplayacak.

Script'in tam içeriği kaynak kod paketinde (`clockdiag.sh`) mevcut.

## 11. LSPosed hook + KernelSU root modülü — **UYGULANDI**

**Güncelleme: bu bölüm artık tamamlandı, aşağıdaki orijinal plan tarihsel
bağlam için korunuyor.** Gerçek uygulanan hali:

- `kernelsu-module/` adında ayrı bir KernelSU modülü eklendi:
  `module.prop`, `service.sh` (loopback-only `busybox httpd`, `/data/adb/
  ksu/bin/busybox` yolu tercih edilip yoksa PATH'teki `busybox`'a düşüyor),
  `webroot/index.html` (tam ayar arayüzü, eski Activity UI'sinin birebir
  karşılığı), `webroot/cgi-bin/load.sh` + `save.sh` (config oku/yaz + aynı
  token'lı broadcast'i root bağlamından gönder).
- **Önemli düzeltme:** İlk taslakta (bir üst-AI'nin önerisinde) "`/data/
  local/tmp/iosclockblur/config.json`'a hook tarafında yeni bir loader
  eklenmeli" deniyordu — **bu yanlıştı, o loader zaten mevcuttu**
  (`ROOT_CONFIG_PATH` sabiti + `readRootConfig()`, bu belgenin ilk
  sürümünde de §6.1'de belgelenmişti). Gerçek eksik olan tek şey o dosyayı
  **kimin yazdığıydı** — Activity'nin `su -c` hack'i yerine artık KernelSU
  WebUI'nin CGI script'i yazıyor.
- `ClockBlurHook.kt` ve `Prefs.kt` **hiç değiştirilmedi** — render pipeline
  (blur/tint/matrix/doze mantığı) bu geçişte bilinçli olarak dokunulmadı,
  tam da §9'daki "reddedilenler" prensibiyle tutarlı (ayrı bir üst-AI'nin
  önerdiği "V2: ayrı cam panel View + RenderEffect + Liquid Shader" mimarisi
  de aynı gerekçeyle reddedildi — çalışan, doğrulanmış bir pipeline'ı
  gerekçesiz karmaşıklaştırıyordu, ayrıca boş bir View üzerine `RenderEffect`
  koymanın arkasındaki wallpaper'ı otomatik yakalamayacağı gibi teknik bir
  hata da içeriyordu).
- `MainActivity.kt` sadeleştirildi: artık ayar tutmuyor, sadece WebUI'ye
  yönlendiren bir bilgi ekranı (`http://127.0.0.1:8765` açan bir buton).
- README güncellendi: iki bileşenli kurulum (LSPosed APK + KernelSU modülü
  ayrı ayrı kurulmalı).

**Hâlâ doğrulanmamış/açık:** `busybox httpd`'nin bu ROM'da gerçekten
`/data/adb/ksu/bin/busybox` yolunda bulunup bulunmadığı, CGI script'lerin
(`dd bs=1 count=$CONTENT_LENGTH` ile body okuma) bu ROM'un busybox
sürümünde sorunsuz çalışıp çalışmadığı — **hiçbiri gerçek cihazda test
edilmedi.** İlk kurulumda muhtemelen küçük script hataları çıkabilir, bunlar
`service.sh`'i manuel çalıştırıp (`su -c sh /data/adb/modules/
iosclockblur_webui/service.sh`) hata mesajını görerek debug edilmeli.

---

### Orijinal plan (tarihsel referans, artık uygulandı)

**Kullanıcının bu belgeyle birlikte açıkça istediği değişiklik:** Şu anki
ayarlar mimarisi (§6.1 — Activity'den runtime `su` çağrısı + XSharedPreferences
fallback) yerine, kullanıcının **kendi kurulu, alışık olduğu paterne**
geçmek isteniyor: **LSPosed (hook için) + KernelSU root modülü (ayarlar
için, WebUI ile)**.

### 11.1 Neden bu daha mantıklı

- Kullanıcının zaten kendi ürettiği, bakımını yaptığı, debug etmeyi bildiği
  bir pattern var (Smart Cache Cleaner, SystemSweep) — WebUI + KernelSU
  Next Promise API. Yeni/tek seferlik bir "Activity'den su çağır" hack'i
  değil, bilinen bir yol.
- Config dosyası KernelSU modülünün kendi dizininde (`/data/adb/modules/
  iosclockblur/...`) yaşarsa, **doğası gereği root-owned** olur — ayrı bir
  "bu app'e root ver" isteğine gerek kalmaz, mevcut KernelSU modül
  altyapısının bir parçası olur.
- XSharedPreferences + SELinux kırılganlığı (§6.1'deki bilinen risk)
  **tamamen ortadan kalkar** — WebUI'nin `ksu.exec()` köprüsü zaten root
  bağlamında çalışıyor, ayrı bir okuma/yazma izin sorunu olmuyor.
- Kullanıcının diğer projeleriyle (aynı KernelSU modül listesinde görünme,
  aynı güncelleme/etkinleştirme akışı) tutarlı olur.

### 11.2 Ne değişmeden kalıyor

LSPosed hook'unun kendisi (`ClockBlurHook.kt`, SystemUI'ye enjekte edilen
kod) **mutlaka ayrı bir Android APK olarak kalmak zorunda** — Xposed/LSPosed
modülleri PackageManager üzerinden `xposedmodule` meta-data'sı taranarak
keşfediliyor, bu APK olmadan olmuyor. Değişecek olan sadece **ayarların
nereden okunduğu/yazıldığı**.

### 11.3 Önerilen yeni yapı (taslak, geliştirilmeli)

```
iosclockblur-module/            (KernelSU modülü — /data/adb/modules/iosclockblur/ olarak kurulur)
├── module.prop
├── service.sh veya post-fs-data.sh   (gerekirse config dizinini hazırlar)
├── webroot/
│   └── index.html               (ayarlar arayüzü — mevcut Kotlin Activity
│                                  UI'sinin (switch/seekbar) web karşılığı,
│                                  await ksu.exec(cmd) ile config.json'a yazar)
└── (config.json burada, modülün kendi dizininde yaşar)

iosclockblur-hook/               (ayrı APK — LSPosed modülü, SADECE hook)
└── ... (ClockBlurHook.kt, XSharedPreferences kodu ve root-exec kodu
         tamamen çıkarılır; sadece /data/adb/modules/iosclockblur/config.json'ı
         okur — KernelSU modülünün dizini olduğu için SystemUI process'i
         zaten güvenilir şekilde okuyabilir olmalı, ama bu da doğrulanmalı)
```

**Açık sorular / doğrulanması gerekenler (bu üst-AI'nin araştırması
gerekebilir):**

1. `/data/adb/modules/<id>/` dizini, SystemUI gibi sistem process'lerinden
   normal dosya okuma ile (root gerekmeden, sadece Unix izinleriyle)
   okunabilir mi, yoksa bu da SELinux'a mı takılır? (KernelSU modül
   dizinleri genelde `overlay` ile mount ediliyor, izin modeli farklı
   olabilir — araştırılmalı.)
2. Eğer SystemUI o dizini okuyamıyorsa, alternatif: config'i yine
   `/data/local/tmp/` gibi evrensel okunabilir bir yere KOPYALAMAK için
   modülün bir `service.sh`/`post-fs-data.sh` script'i kullanması (WebUI
   `/data/adb/modules/iosclockblur/config.json`'a yazar, bir arka plan
   script bunu periyodik ya da dosya-değişince `/data/local/tmp/`'ye
   kopyalar).
3. Kullanıcının Smart Cache Cleaner modülünün tam yapısı (module.prop
   formatı, WebUI'nin JS köprüsünü nasıl kullandığı) referans alınabilir —
   kullanıcıdan o projenin kaynak kodu istenebilir, aynı paternleri
   tekrarlamak tutarlılık sağlar.

Bu bölümdeki tasarımı **tamamlamak, doğrulamak ve gerçek kodla uygulamak bu
belgeyi alan AI'nin görevi.**

## 12. Şu anki kaynak kod (mimari değişiklikten ÖNCEKİ, çalışan son hâli)

Aşağıdaki kod **şu an gerçek cihazda çalışıyor ve §6.5'teki görsel sonucu
üretiyor.** Bu, §11'deki mimari değişikliğin başlangıç noktası —sıfırdan
yazmak yerine bunun üzerine inşa edilmeli, özellikle blur/tint/matrix/doze
mantığı (§5, §6.3, §6.4) **aynen korunmalı**, sadece ayarların okunduğu
kaynak (§6.1 → §11) değişmeli.

Tam kaynak kod ayrıca ayrı dosyalar halinde ekte (zip). Referans için burada
da dosya listesi:

- `app/src/main/java/com/omersusin/iosclockblur/ClockBlurHook.kt` (~610
  satır) — asıl hook, SystemUI process'inde çalışır
- `app/src/main/java/com/omersusin/iosclockblur/MainActivity.kt` (~257
  satır) — **bu dosya §11 değişikliğiyle büyük ölçüde gereksizleşecek/
  WebUI'ye taşınacak**
- `app/src/main/java/com/omersusin/iosclockblur/Prefs.kt` — paylaşılan
  sabitler
- `app/src/main/AndroidManifest.xml`, `app/build.gradle`, `build.gradle`,
  `settings.gradle`, `gradle.properties`
- `.github/workflows/build.yml` — GitHub Actions ile APK derleme (Gradle
  wrapper JAR'sız, `gradle/actions/setup-gradle@v4` kullanıyor — kullanıcının
  ağ kısıtlı ortamında wrapper indirilemediği için)
- `clockdiag.sh` — §10'daki diagnostic script

## 13. Bilinen sınırlamalar (güncel liste)

1. **Tek cihaz/tek ROM'da test edildi** (POCO F5, bu spesifik LineageOS
   tabanlı build). Başka ROM'da reflection alan adları (`textClocks`,
   `styledTextViews`) farklı olabilir — view-tree fallback var ama başka
   hiçbir ROM'da test edilmedi.
2. **Ekran döndürme test edilmedi.**
3. **Canlı duvar kağıdı (live wallpaper) tespiti kodda var ama hiç
   tetiklenmedi** (kullanıcı hep statik duvar kağıdı kullandı).
4. **`force_software_layer` ayarı açık test edildi** (ekran görüntülerinde
   "Uyumluluk modu" hep AÇIK görünüyor) — kapalıyken (donanım hızlandırmalı
   yol) sorunsuz çalışıp çalışmadığı doğrulanmadı.
5. **XSharedPreferences + SELinux riski hâlâ teorik olarak mevcut** (§6.1) —
   §11'deki mimari değişiklik bunu tamamen ortadan kaldırmayı hedefliyor.
6. **Root-yazılan JSON dosyası için kullanıcının ayrıca bu app'e root
   vermesi gerekiyor** — bu, §11 değişikliğinin bir diğer motivasyonu.
7. Native "Derinlik Duvar Kağıdı" (§8) ile bizim modülün **birlikte** nasıl
   davrandığı henüz test edilmedi.
8. Diagnostic script henüz sadece POCO F5'te çalıştı; HyperOS/ColorOS
   verisi bekleniyor.

## 14. Bu AI'dan istenen

1. **§11'deki KernelSU/WebUI mimarisine geçişi tamamla** — gerçek
   `module.prop`, `webroot/index.html`, gerekirse `service.sh`, ve
   `ClockBlurHook.kt`'nin XSharedPreferences/root-exec kısımlarının
   çıkarılıp basit bir dosya okumasına indirgenmiş hâli.
2. §11.3'teki açık soruları (özellikle #1: SystemUI'nin `/data/adb/modules/`
   dizinini okuyup okuyamayacağı) araştır/doğrula, gerekirse alternatif
   (#2) yolu uygula.
3. Mevcut çalışan görsel mantığı (§5, §6.2'deki 12/6/170 varsayılanları,
   §6.3 doze handling, §6.4 async blur) **bozmadan** koru.
4. Eğer HyperOS/ColorOS diagnostic verisi bu konuşmada mevcutsa, §9'daki
   "reddedildi" kararını yeniden değerlendirmek için kullan — veri
   yoksa spekülatif evrensel kod yazma, §9'daki gerekçe hâlâ geçerli.
