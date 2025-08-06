package com.t91s.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.content.res.AssetManager
import java.io.File
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebChromeClient
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream
import java.io.IOException
import android.os.Handler
import android.os.Looper
import okio.sink
import okio.buffer
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.provider.MediaStore
import android.content.ContentValues
import android.util.Patterns

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib") // Carga la librería nativa generada por CMake
        }
        var _startedNodeAlready = false
    }

    external fun startNodeWithArguments(arguments: Array<String>): Int

    fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            val files = assetManager.list(fromAssetPath)
            if (files!!.isEmpty()) {
                // It's a file
                assetManager.open(fromAssetPath).use { `in` ->
                    FileOutputStream(File(toPath)).use { out ->
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (`in`.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                        out.flush()
                    }
                }
                return true
            } else {
                // It's a folder
                val dir = File(toPath)
                if (!dir.exists()) dir.mkdirs()
                for (file in files) {
                    copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private val REQUEST_BLUETOOTH_CONNECT = 1001
    private var pendingPrinterName: String? = null
    private var pendingPrinterMac: String? = null
    private val REQUEST_STORAGE_PERMISSION = 2001
    private var pendingDownloadUrl: String? = null

    // Bridge para exponer impresión Bluetooth a JavaScript
    inner class JsBridge {
        @JavascriptInterface
        fun printBluetooth(printerMac: String?) {
            Log.i("AndroidBridge", "[AndroidBridge] printBluetooth llamado desde JS con printerMac: $printerMac")
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.i("AndroidBridge", "[AndroidBridge] Solicitando permiso BLUETOOTH_CONNECT")
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        REQUEST_BLUETOOTH_CONNECT
                    )
                    pendingPrinterMac = printerMac
                } else {
                    Log.i("AndroidBridge", "[AndroidBridge] Permiso BLUETOOTH_CONNECT concedido, llamando a doBluetoothPrint")
                    doBluetoothPrint(printerMac)
                }
            }
        }

        @JavascriptInterface
        fun getPairedBluetoothPrinters(): String {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_CONNECT
                )
                return "[]"
            }
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val printers = pairedDevices?.map {
                mapOf("name" to it.name, "mac" to it.address)
            } ?: emptyList()
            return try {
                com.google.gson.Gson().toJson(printers)
            } catch (e: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun testReturn(): String {
            return "hola desde android"
        }
    }

    private fun doBluetoothPrint(printerMac: String?) {
        Log.i("AndroidBridge", "[AndroidBridge] doBluetoothPrint llamado con printerMac: $printerMac")
        val helper = BluetoothPrinterHelper(this@MainActivity)
        Thread {
            val (ok, usedPrinterName, errorMsg) = helper.printFile(printerMac)
            Log.i("AndroidBridge", "[AndroidBridge] Resultado de printFile: ok=$ok, usedPrinterName=$usedPrinterName, errorMsg=$errorMsg")
            runOnUiThread {
                val msg = if (ok) {
                    "Impresión Bluetooth enviada a: ${usedPrinterName ?: "(desconocida)"}"
                } else {
                    "Error al imprimir por Bluetooth${if (usedPrinterName != null) ": $usedPrinterName" else ""}${if (!errorMsg.isNullOrBlank()) "\n$errorMsg" else ""}"
                }
                Toast.makeText(
                    this@MainActivity,
                    msg,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No se requiere permiso de almacenamiento para descargas en Android 10+
            true
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission(downloadUrl: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingDownloadUrl = downloadUrl
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mimeType)
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        request.setTitle(fileName)
        request.setDescription("Descargando archivo...")
        request.allowScanningByMediaScanner()
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // Solo en Android 10 o menor
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        // En Android 11+ no se establece destino, DownloadManager lo maneja
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
    }

    private fun sanitizeFileName(fileName: String): String {
        // Solo permite letras, números, guiones, guion bajo y punto
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun extractFileNameFromHeader(disposition: String?): String? {
        if (disposition == null) return null
        val regex = Regex("filename=\"?([^\";]+)\"?")
        return regex.find(disposition)?.groupValues?.get(1)
    }

    private fun downloadFileWithSession(
        url: String,
        originalFileName: String,
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Solicitar permiso en Android 9 o menor
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                pendingDownloadUrl = url
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION
                )
            }
            return
        }
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", cookies ?: "")
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Handler(Looper.getMainLooper()).post {
                    Log.e("DownloadDebug", "Fallo en la descarga: ${e.message}")
                    onError(e)
                }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (!response.isSuccessful) {
                    Handler(Looper.getMainLooper()).post {
                        Log.e("DownloadDebug", "Respuesta HTTP no exitosa: ${response.code}")
                        onError(IOException("Error en la descarga: ${response.code}"))
                    }
                    return
                }
                // Extraer nombre amigable del header si existe
                val headerFileName = extractFileNameFromHeader(response.header("Content-Disposition"))
                val fileName = sanitizeFileName(headerFileName ?: originalFileName)
                val file: File
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        // Usar MediaStore para guardar en Descargas
                        val resolver = contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, getMimeType(fileName))
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val itemUri = resolver.insert(collection, contentValues)!!
                        resolver.openOutputStream(itemUri).use { outputStream ->
                            response.body?.byteStream()?.copyTo(outputStream!!)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, contentValues, null, null)
                        // Obtener la ruta real del archivo (opcional, para notificación)
                        file = File("/storage/emulated/0/Download/$fileName")
                        Log.d("DownloadDebug", "Archivo guardado con MediaStore: ${file.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("DownloadDebug", "Error guardando con MediaStore: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            onError(e)
                        }
                        return
                    }
                } else {
                    // Android 9 o menor: guardar directamente
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    file = File(downloadsDir, fileName)
                    val sink = file.sink().buffer()
                    response.body?.source()?.let { sink.writeAll(it) }
                    sink.close()
                    Log.d("DownloadDebug", "Archivo guardado directamente: ${file.absolutePath}")
                }
                Handler(Looper.getMainLooper()).post {
                    onSuccess(file)
                    showDownloadNotification(file)
                }
            }
        })
    }

    private fun showDownloadNotification(file: File) {
        val channelId = "download_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Descargas", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file.name))
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Descarga completada")
            .setContentText(file.name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(file.name.hashCode(), notification)
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf" -> "application/pdf"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
    }

    private fun openDownloadedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el archivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doBluetoothPrint(pendingPrinterMac)
            } else {
                Toast.makeText(this, "Permiso Bluetooth denegado", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl?.let {
                    // Reintentar descarga después de conceder permiso
                    val fileName = URLUtil.guessFileName(it, null, null)
                    downloadFileWithSession(
                        it,
                        fileName,
                        onSuccess = { file ->
                            Toast.makeText(this, "Descarga completada: ${file.name}", Toast.LENGTH_LONG).show()
                        },
                        onError = { e ->
                            Toast.makeText(this, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                    pendingDownloadUrl = null
                }
            } else {
                Toast.makeText(this, "Permiso de almacenamiento denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Habilitar debug remoto para WebView
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        // Copiar nodejs-project de assets a filesDir si no existe
        val nodeDir = File(filesDir, "nodejs-project")
        if (!nodeDir.exists()) {
            copyAssetFolder(assets, "nodejs-project", nodeDir.absolutePath)
        }

        // Arrancar Node.js Mobile
        if (!_startedNodeAlready) {
            _startedNodeAlready = true
            Log.i("NodeJSMobile", "Lanzando hilo de Node.js Mobile...")
            Thread {
                Log.i("NodeJSMobile", "Ejecutando startNodeWithArguments...")
                startNodeWithArguments(arrayOf("node", File(filesDir, "nodejs-project/index.js").absolutePath))
                Log.i("NodeJSMobile", "startNodeWithArguments terminó")
            }.start()
        }

        // WebView
        val webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        // Agregar WebChromeClient para redirigir console.log a Logcat
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d("WebViewConsole", "${message.message()} -- Línea: ${message.lineNumber()} (${message.sourceId()})")
                return true
            }
        }
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        //webView.loadUrl("https://t91s.adcotec.com.mx")
        webView.loadUrl("https://app.adcotec.com.mx")
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            fileName = sanitizeFileName(fileName)
            Log.d("DownloadDebug", "Nombre de archivo seguro: $fileName")
            downloadFileWithSession(
                url,
                fileName,
                onSuccess = { file ->
                    Log.d("DownloadDebug", "Archivo guardado en: ${file.absolutePath}")
                    Toast.makeText(this, "Descarga completada: ${file.name}\nUbicación: Descargas", Toast.LENGTH_LONG).show()
                    openDownloadedFile(file)
                },
                onError = { e ->
                    Log.e("DownloadDebug", "Error al descargar: ${e.message}")
                    Toast.makeText(this, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
}
