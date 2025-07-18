package com.t91s.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebChromeClient

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
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val printers = pairedDevices?.map {
                mapOf("name" to it.name, "mac" to it.address)
            } ?: emptyList()
            return try {
                // Usa Gson para serializar a JSON
                com.google.gson.Gson().toJson(printers)
            } catch (e: Exception) {
                "[]"
            }
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
    }
}
