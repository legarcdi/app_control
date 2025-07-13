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

class MainActivity : ComponentActivity() {

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        //webView.loadUrl("https://t91s.adcotec.com.mx")
        webView.loadUrl("https://app.adcotec.com.mx")
    }
}
