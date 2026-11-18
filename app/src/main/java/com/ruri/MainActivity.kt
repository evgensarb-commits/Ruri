package com.ruri

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
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
        Notifier.init(this)
        requestNotificationPermission()
        startRuriService()

        web.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun startRuriService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RuriService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("Ruri", "Unable to start foreground service", e)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun read(path: String): String = FileManager.read(filesDir, path)

        @JavascriptInterface
        fun write(path: String, content: String): String {
            FileManager.write(filesDir, path, content)
            return "OK"
        }

        @JavascriptInterface
        fun list(path: String): String = FileManager.list(filesDir, path)

        @JavascriptInterface
        fun exists(path: String): Boolean = FileManager.exists(filesDir, path)

        @JavascriptInterface
        fun markCommanderSeen(): String {
            return try {
                val file = File(filesDir, "memory/L4_state.json")
                val state = try {
                    JSONObject(if (file.exists()) file.readText() else "{}")
                } catch (_: Exception) {
                    JSONObject()
                }
                state.put("last_seen_commander", System.currentTimeMillis())
                state.put("waiting_for_reply", false)
                FileManager.write(filesDir, "memory/L4_state.json", state.toString(2))
                "OK"
            } catch (e: Exception) {
                "ERROR: " + e.message
            }
        }

        @JavascriptInterface
        fun importArchive(json: String): String {
            return try {
                val archive = JSONObject(json)
                val keys = archive.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = archive.opt(key)
                    if (value is String) FileManager.write(filesDir, key, value)
                }
                "OK"
            } catch (e: Exception) {
                "ERROR: " + e.message
            }
        }

        @JavascriptInterface
        fun exportArchive(json: String): String {
            return try {
                val directory = File(cacheDir, "exports")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Не удалось создать папку экспорта")
                }
                val archive = File(directory, "ruri_backup_" + System.currentTimeMillis() + ".json")
                archive.writeText(json, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    packageName + ".fileprovider",
                    archive
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "Архив Рури", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {
                    try {
                        startActivity(Intent.createChooser(sendIntent, "Сохранить архив Рури"))
                    } catch (e: Exception) {
                        android.util.Log.e("Ruri", "Unable to share archive", e)
                    }
                }
                "OK"
            } catch (e: Exception) {
                "ERROR: " + e.message
            }
        }
    }
}