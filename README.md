# LoFiCam

Aplikasi kamera Android bergaya retro ("0.3MP look"): foto otomatis diturunkan
resolusinya ke sekitar 0,3 megapiksel, dipiksel-kan (pixelated), diberi grain,
vignette, dan stempel tanggal — mirip kamera digital murah era 2000-an.

100% open source (lisensi MIT), dibuat dengan Kotlin + CameraX, **tanpa**
dependensi berbayar atau tertutup.

## Target perangkat
Dikonfigurasi khusus untuk **HP 64-bit dengan Android 13 (API 33)**:
- `minSdk = 33`, `targetSdk = 33` → hanya terpasang di Android 13 ke atas yang kompatibel API 33
- `abiFilters = arm64-v8a` → APK hanya berisi binary 64-bit, tidak termasuk 32-bit (armeabi-v7a/x86), ukuran APK lebih kecil

Kalau nanti mau dipasang juga di Android lebih lama, turunkan `minSdk` di
`app/build.gradle.kts`.

## Fitur
- Preview kamera langsung (CameraX)
- Efek downsample ke ~0,3MP + pixelation blok 4px
- Color grading ringan (kontras naik, sedikit hangat seperti sensor CCD lama)
- Film grain acak + vignette
- Stempel tanggal ala kamera digital jadul
- Simpan otomatis ke galeri (`Pictures/LoFiCam`)

## Cara build APK dari HP (tanpa Android Studio) — pakai GitHub Actions

Project ini sudah menyertakan `.github/workflows/build-apk.yml` yang membuat
GitHub otomatis meng-compile APK di server mereka. Semua langkah bisa
dilakukan dari browser HP:

1. **Buat akun GitHub** (gratis) di github.com kalau belum punya.
2. Di aplikasi/browser GitHub, buat **repository baru** (tombol "+" > "New
   repository"), beri nama misalnya `lofi-cam`, set ke **Public** atau
   **Private**, lalu buat.
3. Ekstrak zip `LoFiCam-project.zip` ini (HP biasanya punya app File Manager
   yang bisa extract zip, atau pakai app "ZArchiver"/"Files by Google").
4. Di halaman repo GitHub yang baru dibuat, pilih **"uploading an existing
   file"**, lalu upload **semua isi** folder `LoFiCam` (termasuk folder
   tersembunyi `.github`) — kalau browser HP sulit upload folder sekaligus,
   upload per-folder satu-satu (`app`, `.github`, lalu file-file di root).
5. Setelah ter-upload dan ter-commit, buka tab **Actions** di repo tsb.
   Workflow "Build APK" akan otomatis berjalan (atau klik **"Run workflow"**
   kalau belum jalan sendiri).
6. Tunggu 2-5 menit sampai muncul tanda ✅ hijau.
7. Klik hasil run tsb → scroll ke bagian **Artifacts** → unduh
   `LoFiCam-debug-apk.zip` → ekstrak → dapat file `app-debug.apk`.
8. Pindahkan APK itu ke penyimpanan HP, lalu install (aktifkan dulu
   "Install dari sumber tidak dikenal" di pengaturan HP kalau diminta).

**Alternatif kalau mau pakai Android Studio di laptop:**
1. Install Android Studio (gratis): https://developer.android.com/studio
2. `File > Open` folder `LoFiCam`, tunggu Gradle sync.
3. Klik **Run ▶**, atau `Build > Build Bundle(s)/APK(s) > Build APK(s)` untuk
   dapat file APK di `app/build/outputs/apk/debug/app-debug.apk`.

## Struktur project
```
LoFiCam/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/lofi/cam/
│       │   ├── MainActivity.kt       # kamera, capture, simpan foto
│       │   └── LoFiProcessor.kt      # semua logika efek 0.3MP
│       └── res/
│           ├── layout/activity_main.xml
│           ├── drawable/shutter_button.xml
│           └── values/ (strings, colors, themes)
├── build.gradle.kts
└── settings.gradle.kts
```

## Mengubah tingkat "lo-fi"
Semua parameter efek ada di `LoFiProcessor.kt`:
- `TARGET_MEGAPIXELS` — ubah untuk resolusi lebih besar/kecil dari 0,3MP
- `PIXELATE_BLOCK` — ukuran blok piksel (makin besar makin kasar)
- fungsi `addGrain(amount = ...)` — jumlah noise film grain
- fungsi `stampDate` — bisa dihapus/diganti formatnya, atau dimatikan lewat
  `LoFiProcessor.process(bitmap, stampTimestamp = false)`

## Lisensi
MIT — bebas dipakai, diubah, dan dibagikan kembali, termasuk untuk keperluan
komersial, selama menyertakan notice lisensi aslinya.
