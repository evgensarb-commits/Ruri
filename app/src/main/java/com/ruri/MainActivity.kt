package com.ruri

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importFileCallback: ValueCallback<Array<Uri>>? = null
    private val importFileRequestCode = 2002

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileManager.init(filesDir)
        Notifier.init(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.allowUniversalAccessFromFileURLs = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (this@MainActivity.importFileCallback != null) {
                    this@MainActivity.importFileCallback?.onReceiveValue(null)
                }
                this@MainActivity.importFileCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                        }
                    if (intent.action == Intent.ACTION_GET_CONTENT ||
                        intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(intent, importFileRequestCode)
                    true
                } catch (e: Exception) {
                    this@MainActivity.importFileCallback?.onReceiveValue(null)
                    this@MainActivity.importFileCallback = null
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Ruri")

        requestNotificationPermission()
        try { LifeScheduler.ensurePeriodic(this) }
        catch (e: Exception) { android.util.Log.e("Ruri", "Could not schedule periodic life", e) }
        startRuriService()
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Android API; retained for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importFileRequestCode) {
            val callback = importFileCallback
            importFileCallback = null
            val result = if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) arrayOf(uri) else null
            } else null
            callback?.onReceiveValue(result)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            try { Bridge().markCommanderSeen() } catch (_: Exception) {}
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun startRuriService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RuriService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("Ruri", "Foreground service start failed", e)
            Toast.makeText(this, "Не удалось запустить фоновую службу", Toast.LENGTH_LONG).show()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun completeChat(callbackId: String): String {
            if (!callbackId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return "ERROR: invalid callback"
            chatExecutor.execute {
                try {
                    val reply = ChatEngine.respond(filesDir)
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatResult(" + JSONObject.quote(callbackId) + "," +
                                JSONObject.quote(reply) + ",null)", null
                        )
                    }
                } catch (e: Exception) {
                    val error = e.message ?: "Ошибка ответа модели"
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatResult(" + JSONObject.quote(callbackId) + ",null," +
                                JSONObject.quote(error) + ")", null
                        )
                    }
                }
            }
            return "OK"
        }

        @JavascriptInterface
        fun read(path: String): String = FileManager.read(filesDir, path)

        @JavascriptInterface
        fun readNetworkDiagnostics(): String = NetworkDiagnostics.latest(filesDir)

        @JavascriptInterface
        fun readAutomaticBackupStatus(): String = LocalBackupManager.status(filesDir).toString()

        @JavascriptInterface
        fun verifyAutomaticBackup(fileName: String): String {
            val safeName = fileName.takeIf { it.matches(Regex("ruri_auto_[0-9]{8}_[0-9]{6}_[0-9]+\\\\.zip")) }
                ?: return JSONObject().put("valid", false).put("error", "Invalid backup name").toString()
            val result = LocalBackupManager.verify(File(File(filesDir, "backup/automatic"), safeName))
            return JSONObject().put("valid", result.valid).put("error", result.error)
                .put("files", result.files.size).toString()
        }

        @JavascriptInterface
        fun restoreAutomaticBackup(fileName: String): String {
            return try { LocalBackupManager.restore(filesDir, fileName).toString() }
            catch (e: Exception) { "ERROR: " + (e.message ?: "restore failed") }
        }

        @JavascriptInterface
        fun write(path: String, content: String): String {
            return try {
                if (path == "core/config.json") SecretStore.saveRuntimeConfig(filesDir, content)
                else FileManager.write(filesDir, path, content)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "write failed") }
        }

        @JavascriptInterface
        fun readRuntimeConfig(): String {
            return try { SecretStore.readRuntimeConfig(filesDir) }
            catch (e: Exception) { android.util.Log.e("Ruri", "Cannot read runtime config", e); "{}" }
        }

        @JavascriptInterface
        fun saveRuntimeConfig(content: String): String {
            return try { SecretStore.saveRuntimeConfig(filesDir, content); "OK" }
            catch (e: Exception) { "ERROR: " + (e.message ?: "config save failed") }
        }

        @JavascriptInterface
        fun appendEvent(eventJson: String): String {
            return try {
                val event = JSONObject(eventJson)
                FileManager.appendEvent(filesDir, event)
                if (event.optString("type") == "message" && event.optString("who") == "commander") {
                    LifeScheduler.enqueueEvent(this@MainActivity, "commander_message")
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "event append failed") }
        }

        @JavascriptInterface
        fun list(path: String): String = FileManager.list(filesDir, path)

        @JavascriptInterface
        fun exists(path: String): Boolean = FileManager.exists(filesDir, path)

        @JavascriptInterface
        fun markCommanderSeen(): String {
            return try {
                val state = JSONObject(FileManager.read(filesDir, "memory/L4_state.json"))
                state.put("last_seen_commander", System.currentTimeMillis())
                state.put("waiting_for_reply", false)
                state.put("ignored_count", 0)
                FileManager.write(filesDir, "memory/L4_state.json", state.toString(2))
                "OK"
            } catch (e: Exception) { "ERROR: " + e.message }
        }

        @JavascriptInterface
        fun searchWeb(query: String): String = WebSearch.search(query)

        @JavascriptInterface
        fun openNotificationAccessSettings(): String {
            return try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "settings unavailable") }
        }

        @JavascriptInterface
        fun hasUsageAccess(): Boolean = UsageAccessSensor.hasAccess(this@MainActivity)

        @JavascriptInterface
        fun openUsageAccessSettings(): String {
            return try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "usage access settings unavailable") }
        }

        @JavascriptInterface
        fun openUrl(url: String): String {
            return try {
                val uri = Uri.parse(url)
                if (uri.scheme != "https" && uri.scheme != "http") return "ERROR: unsupported URL"
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "cannot open link") }
        }

        @JavascriptInterface
        fun importArchive(json: String): String {
            return try {
                val archive = JSONObject(json)
                LocalBackupManager.createSnapshot(filesDir)
                val allowedRoots = listOf("core/", "memory/", "shelf/", "research/", "skills/", "proposals/")
                val keys = archive.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val value = archive.opt(path)
                    if (value !is String) continue
                    if (path == "core/secrets.enc.json") continue
                    if (!allowedRoots.any { path.startsWith(it) }) continue
                    when (path) {
                        "memory/L0_raw.jsonl" -> FileManager.replaceL0FromArchive(filesDir, value)
                        "core/config.json" -> SecretStore.saveRuntimeConfig(filesDir, value)
                        else -> FileManager.write(filesDir, path, value)
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "invalid archive") }
        }

        @JavascriptInterface
        fun exportArchive(json: String): String {
            return try {
                val directory = File(cacheDir, "exports")
                if (!directory.exists()) directory.mkdirs()
                val archive = File(directory, "ruri_backup_" + System.currentTimeMillis() + ".json")
                archive.writeText(json, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", archive)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "Архив Рури", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {
                    try { startActivity(Intent.createChooser(intent, "Сохранить архив Рури")) }
                    catch (e: Exception) { Toast.makeText(this@MainActivity, "Не удалось открыть экспорт", Toast.LENGTH_LONG).show() }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "export failed") }
        }
    }
}