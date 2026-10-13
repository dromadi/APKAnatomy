# APKAnatomy 🔬

**APKAnatomy** adalah aplikasi Android developer/auditing tool yang dirancang untuk menganalisis, membedah, dan mempelajari arsitektur internal dari file APK (baik aplikasi terinstal maupun file APK standalone).

---

## 🏛️ Konsep Anatomi

Aplikasi ini menggunakan metafora anatomi untuk mengelompokkan komponen aplikasi Android:

* 🦴 **Kerangka (Skeleton):** `AndroidManifest.xml` (Activity, Service, BroadcastReceiver, ContentProvider, Permissions).
* 🧠 **Logika & Otot (Organs & Logic):** Bytecode DEX metrics, Native Libraries (`.so` ABI audit: arm64-v8a, armeabi-v7a, x86_64), Framework runtime detection (Native, Flutter, React Native, Unity).
* 🎨 **Kulit & Estetika (Skin & UI):** Resource allocation, Drawable, Font, Layout, Assets breakdown.
* 🧬 **DNA & Identitas (Identity & Security):** APK Signature Scheme (v1, v2, v3, v4), Certificate Details, Debuggable & Backup Flags, Network Security Config.

---

## 🚀 Fitur Utama (Roadmap Fase 1)

1. **Installed & Standalone APK Analyzer**
   - Menganalisis aplikasi yang terpasang di perangkat via Android `PackageManager`.
   - Menganalisis file APK mentah dari penyimpanan lokal via `ZipFile` parser.
2. **Library & Framework Fingerprinting**
   - Mendeteksi third-party libraries dan SDK arsitektur yang disematkan.
3. **Component & Permission Matrix**
   - Eksplorasi komponen `exported=true` vs `exported=false`.
   - Kategorisasi permission berdasarkan tingkat proteksi.
4. **Keamanan & Validasi Tanda Tangan**
   - Inspeksi SHA-256 fingerprint sertifikat penandatangan.

---

## 🛠️ Tech Stack Target

* **Language:** Kotlin
* **UI:** Jetpack Compose & Material 3
* **Min SDK:** 26 (Android 8.0 Oreo)
* **Target SDK:** 34 / 35 (Android 14 / 15)
* **Architecture:** Clean Architecture + MVVM + Coroutines & Flow

---

## 📄 Lisensi
MIT License
