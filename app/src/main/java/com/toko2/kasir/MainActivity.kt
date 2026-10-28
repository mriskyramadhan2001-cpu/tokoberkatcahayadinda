package com.toko2.kasir

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Pembungkus WebView untuk aplikasi kasir (index.html di folder assets).
 *
 * Kontrak AndroidBridge yang dipanggil oleh JavaScript di index.html:
 *   - printBluetooth(teks)              -> cetak struk (teks ESC/POS) ke printer Bluetooth
 *   - shareImageBase64(base64, nama)    -> buka share sheet (WhatsApp, dll) untuk gambar struk
 *   - sharePdfBase64(base64, nama)      -> buka share sheet untuk PDF struk
 *   - exitApp()                         -> keluar aplikasi
 *   - pilihPrinter()                    -> (tambahan) tampilkan daftar printer untuk dipilih ulang
 *
 * Kamera untuk scan barcode: scanner di index.html memakai getUserMedia (Quagga),
 * jadi di sini cukup memberi izin kamera ke WebView (lihat onPermissionRequest).
 */
@SuppressLint("SetJavaScriptEnabled", "MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$APP_HOST/assets/index.html"
        private const val REQ_CAMERA = 100
        private const val REQ_BLUETOOTH = 101
        private const val PREF_ADDR = "printer_addr"
        private const val PREF_NAME = "printer_name"
    }

    private lateinit var webView: WebView

    private var pendingCameraRequest: PermissionRequest? = null
    private var pendingPrint: String? = null
    private var pendingChoose = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val prefs by lazy { getSharedPreferences("kasir", Context.MODE_PRIVATE) }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        )
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true                  // localStorage dipakai aplikasi
            mediaPlaybackRequiresUserGesture = false  // suara "tiit" setelah transaksi
            allowFileAccess = false
            allowContentAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == APP_HOST) return false
                // Link luar (WhatsApp, Google, dll) dibuka di aplikasi lain
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: ActivityNotFoundException) {
                    toast("Tidak ada aplikasi untuk membuka link ini")
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // ---- Kamera (scanner barcode) ----
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCamera =
                        request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (request.origin.host != APP_HOST || !wantsCamera) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingCameraRequest = request
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.CAMERA),
                            REQ_CAMERA
                        )
                    }
                }
            }

            // ---- Pilih foto (produk, nota, profil, target) ----
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    toast("Tidak bisa membuka pemilih file")
                    false
                }
            }
        }

        // Bridge harus terpasang SEBELUM halaman dimuat
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // index.html menangani tombol back lewat popstate (tutup modal, dst.)
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) webView.loadUrl(START_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        when (requestCode) {
            REQ_CAMERA -> {
                val req = pendingCameraRequest
                pendingCameraRequest = null
                if (req != null) {
                    if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else {
                        req.deny()
                        toast("Izin kamera ditolak. Aktifkan di Pengaturan > Aplikasi.")
                    }
                }
            }
            REQ_BLUETOOTH -> {
                val text = pendingPrint
                val choose = pendingChoose
                pendingPrint = null
                pendingChoose = false
                if (!ok) {
                    toast("Izin Bluetooth ditolak. Aktifkan di Pengaturan > Aplikasi.")
                } else if (text != null) {
                    printText(text)
                } else if (choose) {
                    choosePrinter(null)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Bridge yang dipanggil dari JavaScript
    // ------------------------------------------------------------------
    inner class Bridge {
        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finishAffinity() }
        }

        @JavascriptInterface
        fun printBluetooth(text: String) {
            runOnUiThread { printText(text) }
        }

        @JavascriptInterface
        fun pilihPrinter() {
            runOnUiThread { choosePrinter(null) }
        }

        @JavascriptInterface
        fun shareImageBase64(base64: String, fileName: String) {
            shareFile(base64, fileName, "image/png")
        }

        @JavascriptInterface
        fun sharePdfBase64(base64: String, fileName: String) {
            shareFile(base64, fileName, "application/pdf")
        }
    }

    // ------------------------------------------------------------------
    // Printer Bluetooth (Bluetooth Classic / SPP, printer thermal umum)
    // ------------------------------------------------------------------
    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH
        )
    }

    private fun printText(text: String) {
        if (!hasBluetoothPermission()) {
            pendingPrint = text
            requestBluetoothPermission()
            return
        }
        val bt = bluetoothAdapter()
        if (bt == null) {
            toast("HP ini tidak punya Bluetooth")
            return
        }
        if (!bt.isEnabled) {
            toast("Nyalakan Bluetooth dulu, lalu cetak lagi")
            return
        }
        val addr = prefs.getString(PREF_ADDR, null)
        if (addr == null) choosePrinter(text) else sendToPrinter(addr, text)
    }

    /** Tampilkan printer yang sudah di-pair di HP, simpan pilihan, lalu cetak kalau ada teks tertunda. */
    private fun choosePrinter(thenPrint: String?) {
        if (!hasBluetoothPermission()) {
            pendingPrint = thenPrint
            pendingChoose = thenPrint == null
            requestBluetoothPermission()
            return
        }
        val bt = bluetoothAdapter()
        if (bt == null || !bt.isEnabled) {
            toast("Nyalakan Bluetooth dulu")
            return
        }
        val devices: List<BluetoothDevice> = bt.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Belum ada printer")
                .setMessage(
                    "Pasangkan (pair) printer lebih dulu di Pengaturan > Bluetooth HP, " +
                        "lalu coba cetak lagi."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = devices.map { "${it.name ?: "Tanpa nama"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih printer")
            .setItems(labels) { _, index ->
                val dev = devices[index]
                prefs.edit()
                    .putString(PREF_ADDR, dev.address)
                    .putString(PREF_NAME, dev.name ?: "")
                    .apply()
                toast("Printer: ${dev.name ?: dev.address}")
                if (thenPrint != null) sendToPrinter(dev.address, thenPrint)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun sendToPrinter(addr: String, text: String) {
        toast("Mencetak...")
        thread {
            var socket: BluetoothSocket? = null
            try {
                val bt = bluetoothAdapter() ?: throw IOException("Bluetooth tidak tersedia")
                val device = bt.getRemoteDevice(addr)
                socket = connectSocket(device)

                // ISO_8859_1: tiap karakter = 1 byte, jadi kode ESC/POS (\x1B, \x1D, \x00) utuh
                val data = text.toByteArray(Charsets.ISO_8859_1)
                val out = socket.outputStream
                var i = 0
                while (i < data.size) {
                    val n = minOf(512, data.size - i)
                    out.write(data, i, n)
                    out.flush()
                    i += n
                    Thread.sleep(30) // beri napas supaya buffer printer murah tidak penuh
                }
                Thread.sleep(1000) // tunggu data habis tercetak sebelum koneksi ditutup
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Gagal mencetak")
                        .setMessage(
                            "${e.message ?: "Printer tidak terhubung"}\n\n" +
                                "Pastikan printer menyala, berada dekat HP, dan tidak sedang " +
                                "dipakai aplikasi lain."
                        )
                        .setPositiveButton("Ganti printer") { _, _ -> choosePrinter(text) }
                        .setNegativeButton("Tutup", null)
                        .show()
                }
            } finally {
                try {
                    socket?.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /** Coba beberapa cara membuka koneksi, karena printer thermal murah suka beda-beda. */
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(sppUuid) },
            { device.createInsecureRfcommSocketToServiceRecord(sppUuid) },
            {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType!!)
                    .invoke(device, 1) as BluetoothSocket
            }
        )
        var lastError: Exception? = null
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                lastError = e
                try {
                    s?.close()
                } catch (ignored: Exception) {
                }
                Thread.sleep(300)
            }
        }
        throw lastError ?: IOException("Tidak bisa terhubung ke printer")
    }

    // ------------------------------------------------------------------
    // Share gambar / PDF struk (WhatsApp, dll)
    // ------------------------------------------------------------------
    private fun shareFile(base64: String, fileName: String, mime: String) {
        thread {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(dir, safeName)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                runOnUiThread {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Kirim struk"))
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal membagikan: ${e.message}") }
            }
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
