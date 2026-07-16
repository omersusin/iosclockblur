# iOS Blur Clock (LSPosed modulu)

ROM'un yerlesik "Ozel Saat Stili" (ClockStyle.java) sistemindeki saat rakamlarini,
duvar kagidinin bulaniklastirilmis ve ekrandaki gercek konumuna hizalanmis bir
versiyonuyla dolduran bir Xposed/LSPosed modulu. Sadece saydamlik (alpha) degil,
gercek "buzlu cam" gorunumu.

## Nasil calisiyor

- `com.android.systemui.clocks.ClockStyle.updateClockView()` metodu hook'lanir.
- Bu metod calistiktan sonra, sinifin `textClocks` alanindaki her `TextClock`
  icin: duvar kagidini kucult -> bulaniklastir (3 gecisli box blur) -> o
  view'in ekrandaki konumuna gore kirpip `BitmapShader` olarak `Paint`'e basar.
- Kilit ekrani arkasinda gercekten sadece duvar kagidi oldugu icin (baska hicbir
  sey saatin arkasindan gecmiyor), statik bulanik bir kopya gercek "canli" blur
  ile ayni gorunur.
- Duvar kagidi degisirse (`ACTION_WALLPAPER_CHANGED`) onbellek temizlenir.

## Mimari (v3): iki ayrı bileşen

Proje artık **iki ayrı parça**dan oluşuyor, ikisi de kurulmalı:

1. **`app/`** — LSPosed modülü (APK). Sadece SystemUI hook'unu taşır
   (`ClockBlurHook.kt`). Kendi başına hiçbir ayar arayüzü yok artık.
2. **`kernelsu-module/`** — KernelSU root modülü. `/data/local/tmp/
   iosclockblur/config.json` dosyasını yöneten bir WebUI sunar
   (`http://127.0.0.1:8765`, sadece loopback'ten erişilebilir).

`ClockBlurHook.kt` her zaman önce bu JSON dosyasını okur (`readRootConfig()`),
bulamazsa `XSharedPreferences`'a (artık kimse yazmıyor, sessizce başarısız
olur), o da olmazsa koddaki sabit varsayılanlara düşer. Bu davranış
değişmedi — sadece dosyayı **kim yazıyor** değişti: eskiden APK içindeki bir
Activity `su -c` ile yazıyordu, şimdi KernelSU modülünün WebUI'si yazıyor.

**Neden bu şekilde:** `/data/adb/modules/` dizini SystemUI'nin SELinux
domain'inden (`system_app`) okunamıyor (`adb_data_file` etiketi), o yüzden
config hâlâ `/data/local/tmp/` altında yaşıyor — sadece onu artık ayrı bir
app'e "root ver" diye sormak yerine, kullanıcının zaten güvendiği KernelSU
modül altyapısı yazıyor.

## Kurulum

**1) LSPosed modülü:**

1. Bu repoyu GitHub'a pushla, Actions sekmesinden "Build iOS Blur Clock Module"
   workflow'unun bitmesini bekle, `iosclockblur-debug-apk` artifact'ini indir.
2. APK'yi telefona kur (bilinmeyen kaynaklara izin gerekebilir).
3. LSPosed Yoneticisi'ni ac -> Moduller -> "iOS Blur Clock" -> etkinlestir ->
   kapsam (scope) olarak **System UI**'yi sec.
4. Telefonu yeniden baslat (Zygisk hook'lari genelde reboot ister).
5. Ayarlar -> Kilit ekrani -> Ozel Saat Stili -> bir stil sec (orn. Sternum,
   ios1-ios19 vs.) -> Apply.

**2) KernelSU WebUI modülü (ayarlar için):**

1. `kernelsu-module/` klasörünü zip'le (içindekiler kökte olacak şekilde,
   klasörün kendisi değil) ya da KernelSU Yöneticisi'nin "Modüller > +"
   ekranından doğrudan klasör olarak kur.
2. KernelSU Yöneticisi'nde modülü etkinleştir, reboot.
3. Modülün yanındaki WebUI ikonuna dokun (ya da LSPosed APK'sındaki
   "Ayarları Aç" butonunu kullan) -> `http://127.0.0.1:8765` açılır.
4. Slider'ları ayarla, **Kaydet**'e bas.

## v2 degisiklikleri (ikinci AI inceleme turundan sonra)

Kabul edilip uygulananlar:
- Reflection alan adlari (`textClocks`/`styledTextViews`) bulunamazsa/bossa
  view-tree taramasina otomatik dusuyor.
- Kilit ekrani duvar kagidi once `FLAG_LOCK`, sonra `FLAG_SYSTEM`, en son
  `WallpaperManager.drawable` sirasiyla deneniyor.
- **AOD/Doze**: `ClockStyle.applyClockAlpha()` de hook'landi - dozing'e
  girildiginde shader kaldiriliyor (arka plan siyah, wallpaper gorunmuyor,
  static buzlu-cam dokusu hem anlamsiz hem burn-in riski).
- Canli duvar kagidi (`wallpaperInfo != null`) tespit edilip nazikce
  atlaniyor (crash/siyah ekran yerine).
- Blur uretimi artik arka plan thread'inde (`Executors.newSingleThreadExecutor`),
  sonuc `Handler(mainLooper)` ile geri donuyor - dozing gecisleri sikca
  oldugu icin jank riskini azaltir.
- Cache anahtarina `getWallpaperId(FLAG_LOCK)` eklendi (wallpaper degisince
  `ACTION_WALLPAPER_CHANGED` gelmese bile fark edilir).
- Ayarlar icin **root-yazilan JSON dosyasi** (`/data/local/tmp/iosclockblur/
  config.json`) birincil kanal oldu, `XSharedPreferences` ikincil/fallback.
  Bunun icin uygulamaya bir kez Superuser izni vermen gerekebilir (KernelSU
  soracak); vermezsen modul yine calisir, sadece eski (daha kirilgan) yoldan.
- Yazi tipi/gorsel bozulursa diye "Uyumluluk modu" (force software layer)
  ayari eklendi, varsayilan kapali.
- Ayarlar broadcast'i artik bir token tasiyor - rastgele baska bir app'in
  ayni action'i gonderip gereksiz repaint tetiklemesini engelliyor.

Bilerek reddedilenler (gerekcesiyle):
- **Status bar offset duzeltmesi** - `getLocationOnScreen()` zaten gercek
  ekran koordinatini donuyor, ekstra cikarma islemi hizalamayi bozar.
- **Box blur kenar davranisini "kritik hata" olarak duzeltmek** - bu standart
  clamp-to-edge yaklasimi, kozmetik ve saat rakamlari ekran kenarina nadiren
  degiyor.
- **Tam "evrensel/ROM-agnostic" mimari** (TextClock.onAttachedToWindow
  genel hook'u + keyguard heuristigi + Compose/canvas overlay katmani) -
  kapsam disi birakildi: tek cihaz/tek ROM icin gereksiz risk, test
  edilemeyen ROM'lar icin kor kod, ve status bar saati gibi yanlis
  TextClock'lari da yakalama riski var.
- Manuel `bitmap.recycle()` cagirma - kendi onerisinde de crash riski
  belirtilmisti (shader hala eski bitmap'i tutuyorsa); GC'ye birakmak daha
  guvenli.

## Ayarlar (v3: KernelSU WebUI)

Artık uygulama içinde ayar ekranı yok. `http://127.0.0.1:8765` (sadece
loopback, telefon dışından erişilemez) üzerinden:
- **Etkin** - kill switch, kapatinca rakamlar normal (bulaniksiz) haline doner
- **Blur siddeti** (2-24, varsayilan 12)
- **Kucultme orani** (3-12, varsayilan 6)
- **Parlaklik / beyazlik katmani** (0-220, varsayilan 170)
- **Tarih yazisini da bulaniklastir**
- **Uyumluluk modu** (force software layer)

Kaydet'e basinca `/data/local/tmp/iosclockblur/config.json`'a yazilir ve
SystemUI'ye token'li bir broadcast gonderilir (`am broadcast` ile, WebUI'nin
kendi CGI script'inden, root baglaminda) - genelde reboot gerekmez, gorunmezse
kilit ekranini bir kere ac/kapa.

**Cozulen risk:** Eskiden ayarlar `XSharedPreferences` (SELinux'a takilabilen
kirilgan yol) ya da uygulama icinden ayrica istenen bir root izniyle
tasiniyordu. Artik config dosyasini KernelSU'nun kendi modul altyapisi
(WebUI -> CGI script, zaten root baglaminda calisiyor) yaziyor - ayri bir
"bu app'e root ver" istegi yok, `XSharedPreferences` sadece (artik kimsenin
yazmadigi, zararsiz) bir fallback olarak kod icinde duruyor.

**Bilinen risk (WebUI tarafi):** `busybox httpd` reboot sonrasi
`service.sh` ile bir kere baslatiliyor, coker/kapanirsa yeniden baslatma
(respawn) mantigi yok - tekrar reboot gerekir. Tek kullanicilik bir arac
icin simdilik kabul edilebilir bir sinirlama.

## Bilinen sinirlamalar / ilk surum notlari

- Sadece `textClocks` (rakamlar) icin uygulaniyor; tarih yazisi ("14 Tem Sal")
  veya marka yazisi ("Sternum") kapsam disi. Onlari da istersen
  `ClockBlurHook.kt` icinde `styledTextViews` listesini de donguye eklemek
  yeterli (tek satir degisiklik).
- Ekran donmesi (rotation) test edilmedi; teorik olarak `w`/`h` degisince
  onbellek otomatik yenileniyor ama gercek cihazda dogrulanmadi.
- `textClocks` alan adi bu ROM build'inin decompile ciktisindan alindi. ROM
  guncellenirse SystemUI.apk'nin field/method isimleri degisebilir, hook
  sessizce calismayabilir (logda hata gorursun, kod atmaz).
- Reflection ile erisilen alan: `textClocks` (ArrayList). Bulunamazsa
  `applyGlassEffect` sessizce return eder, crash olmaz.

## Sorun giderme

```bash
su -c 'logcat -s iOSClockBlur:* Xposed:*'
```

Aranacak satirlar:
- `hooked com.android.systemui.clocks.ClockStyle.updateClockView successfully`
  -> hook basariyla takildi
- `glass shader applied to N TextClock view(s)` -> her saat guncellemesinde
  gorunmeli
- `ClockStyle class not found` -> bu ROM build'inde sinif adi/yolu farkli,
  yeniden decompile edip class adini guncellemek gerekir
- `could not read wallpaper` -> WallpaperManager erisimi basarisiz oldu

## Ayarlanabilir degerler

Artik `ClockBlurHook.kt` icinde sabit degil - WebUI'den (yukaridaki bolum)
degistiriliyor, `config.json` uzerinden okunuyor. `ClockBlurHook.kt`
icindeki tek sabit kalan deger `BLUR_PASSES = 3` (box blur gecis sayisi,
Gaussian yaklasiklik kalitesi) - bunu degistirmek icin kod degisikligi
gerekir.
