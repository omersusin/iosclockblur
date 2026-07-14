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

## Kurulum

1. Bu repoyu GitHub'a pushla, Actions sekmesinden "Build iOS Blur Clock Module"
   workflow'unun bitmesini bekle, `iosclockblur-debug-apk` artifact'ini indir.
2. APK'yi telefona kur (bilinmeyen kaynaklara izin gerekebilir).
3. LSPosed Yoneticisi'ni ac -> Moduller -> "iOS Blur Clock" -> etkinlestir ->
   kapsam (scope) olarak **System UI**'yi sec.
4. Telefonu yeniden baslat (Zygisk hook'lari genelde reboot ister).
5. Ayarlar -> Kilit ekrani -> Ozel Saat Stili -> bir stil sec (orn. Sternum,
   ios1-ios19 vs.) -> Apply.

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

## Ayarlar ekrani

Uygulama simgesine dokununca acilan ekrandan:
- **Etkin** - kill switch, kapatinca rakamlar normal (bulaniksiz) haline doner
- **Blur siddeti** (2-24) - box blur radius
- **Kucultme orani** (3-12) - yuksek deger = daha yumusak + daha hizli, dusuk = daha keskin + daha yavas
- **Tarih yazisini da bulaniklastir** - "14 Tem Sal" gibi yazilari da kapsar

Degisiklikler SystemUI'ye anlik broadcast ile bildirilir; genelde reboot
gerekmez ama gorunmezse kilit ekranini bir kere ac/kapa.

**Bilinen risk:** Ayarlar, modulun kendi process'inden SystemUI process'ine
`XSharedPreferences` (dosya izinleri manipule edilerek) ile aktariliyor. Bu
onlarca yildir kullanilan standart Xposed yontemi ama bazi sertlestirilmis
SELinux politikali ROM'larda calismayabilir. Calismazsa (`logcat`ta
"settings changed, reapplying" mesaji cikiyor ama degerler hep varsayilana
donuyorsa) haber ver, root dosyasi tabanli (`/data/local/tmp/`) daha garanti
bir alternatife gecebiliriz.

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

`ClockBlurHook.kt` en ustteki `companion object` icinde:
- `DOWNSCALE` (varsayilan 6): daha buyuk = daha hizli ama daha "yumusak" blur
- `BLUR_RADIUS` (varsayilan 10) ve `BLUR_PASSES` (varsayilan 3): blur siddeti
