# Özel WebView Kiosk APK (Android 5.0+ / Eski Nesil Uyumlu)

Eski nesil (Android 5.1 / 6.0 - API 21-23) tabletlerde ve modern cihazlarda stabil, hafif (tüy sıklet) ve sıfır harici bağımlılıkla çalışacak şekilde tasarlanmış gömülü Kiosk WebView uygulamasıdır.

---

## 🚀 Temel Özellikler

1. **Geniş Platform Uyumu:**
   - `compileSdk: 33`
   - `minSdk: 21` (Android 5.0 Lollipop desteği)
   - `targetSdk: 28` (Eski tabletlerde arka plan servis kısıtlamalarına takılmadan kararlı çalışma)
2. **Kiosk & Fullscreen WebView Deneyimi:**
   - Açılışta otomatik **Immersive Sticky Fullscreen** (Status bar ve Navigation bar tamamen gizli).
   - Ekranın kapanmasını engelleyen `FLAG_KEEP_SCREEN_ON` ve `WakeLock` mekanizması.
   - Varsayılan URL: `https://kapinet.com.tr/gecis/kiosk.php?token=CinarliGecisKiosk2026`
   - Donanım hızlandırması (`hardwareAccelerated="true"`), DOM Storage, LocalStorage, JavaScript tam aktif.
3. **Gömülü HTTP Dinleyici & Uzaktan Yönetim (Port 8080):**
   - Sıfır harici kütüphane bağımlılığı (Java `ServerSocket` tabanlı ultra hafif multithreaded mimari).
   - **`/kapat`**: Ekran parlaklığını sıfıra (`0.0f`) indirir ve siyah perde overlay'i aktif eder.
   - **`/ac`**: Ekran parlaklığını normale (`1.0f`) getirir, perdeyi kaldırır ve uyanma kilidini (`WakeLock`) tetikler.
   - **`/yenile`**: WebView içeriğini anında yeniler (`reload()`).
   - **`/durum`**: Cihaz IP, Uptime, RAM kullanımı, API seviyesi ve WebView durumunu JSON formatında döner.
4. **Otomatik Başlama (Boot Receiver):**
   - Cihaz yeniden başlatıldığında (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`) uygulamayı otomatik olarak açar.
5. **CI/CD Entegrasyonu:**
   - `.github/workflows/build.yml` ile her `main` branch push'unda veya manuel tetiklemede otomatik `.apk` derlenir ve GitHub Artifacts olarak sunulur.

---

## 📡 Dahili HTTP Sunucu API Dokümantasyonu

Uygulama başladığında tabletin yerel IP adresinde **8080** portundan dinlemeye geçer:

### 1. Ekranı Karart / Kapat
```http
GET http://<TABLET_IP>:8080/kapat
```
**Yanıt (JSON):**
```json
{
  "status": "ok",
  "action": "screen_off",
  "message": "Ekran parlakligi 0.0f yapildi ve karartildi"
}
```

### 2. Ekranı Aç / Uyandır
```http
GET http://<TABLET_IP>:8080/ac
```
**Yanıt (JSON):**
```json
{
  "status": "ok",
  "action": "screen_on",
  "message": "Ekran parlakligi 1.0f yapildi ve uyanma kilidi tetiklendi"
}
```

### 3. WebView Sayfasını Yenile
```http
GET http://<TABLET_IP>:8080/yenile
```
**Yanıt (JSON):**
```json
{
  "status": "ok",
  "action": "reload",
  "message": "WebView yeniden yukleniyor"
}
```

### 4. Cihaz Durumu Sorgulama
```http
GET http://<TABLET_IP>:8080/durum
```
**Yanıt (JSON):**
```json
{
  "status": "online",
  "device_ip": "192.168.1.50",
  "http_port": 8080,
  "uptime": "14:23:05",
  "uptime_seconds": 51785,
  "screen_dimmed": false,
  "memory_used_mb": 18,
  "memory_max_mb": 192,
  "current_url": "https://kapinet.com.tr/gecis/kiosk.php?token=CinarliGecisKiosk2026",
  "android_version": "5.1.1",
  "api_level": 22
}
```

---

## 🛠️ Yerel Ortamda Derleme (Build)

### Gereksinimler:
- JDK 17 (veya JDK 11+)
- Android SDK Build Tools (API 33)

### Derleme Komutları:
```bash
# Debug APK Derleme:
./gradlew assembleDebug

# Release APK Derleme:
./gradlew assembleRelease
```
Üretilen APK dosyaları:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## ⚙️ Cihaza Kurulum ve Kiosk Olarak Ayarlama

1. **APK Yükleme:**
   ```bash
   adb install -r app-debug.apk
   ```

2. **Varsayılan Başlatıcı (Home App / Launcher) Yapma:**
   - Tablet ayarlarından **Ana Sayfa (Home App)** olarak "Çınarlı Kiosk" uygulamasını seçin.
   - Böylece tablet Home tuşuna basıldığında veya yeniden başladığında doğrudan Kiosk ekranı açık kalır.

---

## 📂 Dizin Yapısı

```
.
├── .github/
│   └── workflows/
│       └── build.yml               # GitHub Actions CI/CD hattı
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── app/
│   ├── build.gradle                # MinSdk 21, TargetSdk 28, CompileSdk 33
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # Kiosk izinleri ve Boot Receiver
│           ├── java/com/cinarli/kiosk/
│           │   ├── MainActivity.java      # Tam ekran WebView & Komut Yönetimi
│           │   ├── BootReceiver.java      # Cihaz açılışında otomatik başlama
│           │   └── KioskHttpServer.java   # Port 8080 gömülü HTTP API sunucu
│           └── res/
│               ├── layout/activity_main.xml
│               ├── values/ (colors, strings, styles)
│               ├── xml/network_security_config.xml
│               └── drawable/ic_launcher.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```
