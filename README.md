# Toko 2 Kasir (APK)

Pembungkus Android (WebView) untuk `index.html` kasir. Isi aplikasi ada di
`app/src/main/assets/index.html`.

## Cara build APK TANPA Android Studio (pakai GitHub)

1. Buat akun di github.com, lalu buat repository baru (Private juga boleh).
2. Upload SEMUA isi folder ini ke repository (termasuk folder `.github`).
   Cara gampang: di halaman repository klik "Add file" > "Upload files",
   lalu drag semua isi folder. Pastikan folder `.github/workflows/build.yml` ikut.
3. Buka tab **Actions**. Build jalan otomatis (sekitar 3-6 menit).
   Kalau tidak jalan, pilih "Build APK" > "Run workflow".
4. Setelah centang hijau, klik run tersebut, lalu di bagian **Artifacts**
   download `toko2-apk`. Di dalamnya ada `app-debug.apk`.
5. Kirim APK ke HP, buka, izinkan "Install dari sumber tidak dikenal", lalu install.

## Cara build dengan Android Studio

Buka folder ini di Android Studio > tunggu sync > menu Build > Build APK(s).

## Memakai printer Bluetooth

1. Nyalakan printer, lalu pair di **Pengaturan HP > Bluetooth** (PIN biasanya 0000 atau 1234).
2. Di aplikasi, tekan cetak struk. Pertama kali akan muncul daftar printer, pilih printernya.
   Pilihan tersimpan otomatis.
3. Kalau perlu ganti printer: saat cetak gagal akan muncul tombol "Ganti printer".
   (Opsional: tambahkan tombol di web yang memanggil `AndroidBridge.pilihPrinter()`.)

Catatan: hanya untuk printer Bluetooth Classic (SPP), yaitu jenis printer thermal 58mm/80mm
yang umum. Printer khusus BLE tidak didukung.

## Scan barcode

Scanner di index.html memakai kamera lewat WebView. Saat pertama kali dibuka,
Android akan minta izin kamera, pilih "Izinkan".

## Mengubah isi aplikasi

Ganti `app/src/main/assets/index.html` dengan versi terbaru, lalu build ulang APK.

## Ganti nama / ikon

- Nama: `app/src/main/res/values/strings.xml`
- Ikon: ganti `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (192x192 px)
