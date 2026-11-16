# LoFiCam

Kamera Android bergaya digital/lo-fi retro. Proyek ini masih dalam tahap **beta**; build yang lolos CI belum berarti sudah diuji di semua perangkat.

## Fitur saat ini

- Preview kamera belakang menggunakan CameraX.
- Mode foto dengan efek downscale, color grading, grain, vignette, dan tanggal.
- Mode video dengan resolusi keluaran 1p–150p (diproses setelah rekaman) dan target FPS 5–30.\n- Pilihan format video tersimpan: MP4 atau 3GP.
- Video direkam sementara sebagai MP4, diubah ukurannya dengan Media3, lalu diekspor sebagai MP4 atau dimasukkan ke kontainer 3GP.
- Simpan foto ke `Pictures/LoFiCam` dan video ke `Movies/LoFiCam`.
- APK hanya untuk ABI `arm64-v8a`, Android 13/API 33 ke atas.

## Kelebihan

- Tampilan dan hasil foto diarahkan ke estetika kamera digital jadul.
- Pengaturan target resolusi/FPS tersedia langsung di aplikasi.
- Tidak memerlukan akun atau koneksi internet untuk mengambil foto/video.
- File hasil disimpan ke galeri perangkat.

## Kekurangan dan batasan yang diketahui

- FPS 5–30 adalah permintaan ke kamera dan dapat disesuaikan perangkat. Resolusi keluaran diproses setelah rekaman; pilihan 1p–15p menggunakan batas encoder minimum sekitar 16 piksel.
- Efek grain/vignette/color-grade foto belum diterapkan ke video. Video di-encode ulang untuk ukuran keluaran, lalu diekspor sesuai format pilihan.
- 3GP tidak mendukung semua kombinasi codec. Jika codec video/audio dari perangkat tidak cocok, penyimpanan video dapat gagal.
- Kualitas audio video mengikuti codec dan konfigurasi perekaman perangkat; label 3GP bukan jaminan audio AMR-NB.
- Belum ada pengujian otomatis pada beragam model HP, kamera depan, pergantian orientasi, atau rekaman berdurasi panjang.
- APK CI saat ini ditandatangani dengan debug key untuk pengujian. Jangan menganggapnya sebagai paket produksi Play Store.

## Kompatibilitas

- Android 13 (API 33) atau lebih baru.
- Perangkat Android 64-bit ARM (`arm64-v8a`).
- Kamera belakang dan izin kamera diperlukan.
- Izin mikrofon opsional; tanpa izin ini video direkam tanpa audio.

## Build dan unduh\n\nRelease build menggunakan R8 dan resource shrinking untuk mengurangi ukuran APK. Ukuran 20 MB bukan target minimum; APK tidak sengaja diperbesar dengan data kosong karena hanya menambah unduhan dan pemakaian penyimpanan.\n

GitHub Actions menjalankan `gradle assembleDebug` setiap ada push ke `main`. Jika build berhasil, APK tersedia sebagai artifact bernama **LoFiCam-debug-apk** pada tab Actions.

## Status rilis

Belum dinyatakan stabil/production-ready sampai build CI sukses dan pengujian langsung di perangkat fisik selesai.

## Lisensi

MIT.
