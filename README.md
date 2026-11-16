# LoFiCam

Aplikasi kamera Android bergaya retro ("0.3MP look"): foto otomatis diturunkan
resolusinya ke sekitar 0,3 megapiksel, dipiksel-kan (pixelated), diberi grain,
vignette, dan stempel tanggal — mirip kamera digital murah era 2000-an.

100% open source (lisensi MIT), dibuat dengan Kotlin + CameraX.

## Target perangkat
Dikonfigurasi khusus untuk HP 64-bit dengan Android 13 (API 33):
- `minSdk = 33`, `targetSdk = 33`
- `abiFilters = arm64-v8a` (64-bit saja)

## Fitur
- Preview kamera langsung (CameraX)
- Efek downsample ke ~0,3MP + pixelation blok 4px
- Color grading ringan ala sensor CCD lama
- Film grain acak + vignette
- Stempel tanggal ala kamera digital jadul
- Simpan otomatis ke galeri (Pictures/LoFiCam)

## Build otomatis via GitHub Actions
Repo ini sudah punya `.github/workflows/build-apk.yml`. Setelah semua file
ter-commit ke branch `main`, buka tab Actions, tunggu sampai centang hijau,
lalu unduh APK dari bagian Artifacts (`LoFiCam-debug-apk`).

## Lisensi
MIT — bebas dipakai, diubah, dan dibagikan kembali.
