package com.ruri

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Ruri")

        FileManager.init(filesDir)
        startForegroundService(Intent(this, RuriService::class.java))

        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun read(path: String): String = FileManager.read(filesDir, path)

        @JavascriptInterface
        fun write(path: String, content: String) = FileManager.write(filesDir, path, content)

        @JavascriptInterface
        fun list(path: String): String = FileManager.list(filesDir, path)

        @JavascriptInterface
        fun exists(path: String): Boolean = FileManager.exists(filesDir, path)
        
        @JavascriptInterface
        fun importArchive(json: String) {
        val archive = org.json.JSONObject(json)
        val keys = archive.keys()
        while (keys.hasNext()) {
        val key = keys.next()
        FileManager.write(filesDir, key, archive.getString(key))
    }
}
    }
}
