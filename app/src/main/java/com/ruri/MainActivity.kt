package com.ruri

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importFileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var activityResumed = false
    private var pendingMessageId: String? = null
    private val importFileRequestCode = 2002

    /** Foreground retry timer. Nothing is retried while the screen is off (see onPause). */
    private val autoRetry = Runnable { attemptPendingTurn() }
    private var retryScheduled = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileManager.init(filesDir)
        // Single route, canonical provider section. Never touches memory files.
        Route.ensure(filesDir)
        Notifier.init(this)
        pendingMessageId = intent?.getStringExtra("message_id")
        NetState.register(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) web.settings.safeBrowsingEnabled = true
        web.webViewClient = object : WebViewClient() {
            private fun isTrustedLocalAsset(url: String?): Boolean =
                url == "file:///android_asset/index.html"

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val target = request?.url?.toString()
                if (isTrustedLocalAsset(target)) return false
                if (target != null && (target.startsWith("https://") || target.startsWith("http://"))) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isTrustedLocalAsset(url)) {
                    view?.stopLoading()
                    return
                }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isTrustedLocalAsset(url)) return
                super.onPageFinished(view, url)
                web.evaluateJavascript("window.startOnboarding && window.startOnboarding()", null)
                pushQueueState()
                openPendingMessageIfReady()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                importFileCallback?.onReceiveValue(null)
                importFileCallback = filePathCallback
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
                    importFileCallback?.onReceiveValue(null)
                    importFileCallback = null
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Ruri")

        NetState.refresh(this)
        val onboardingDone = runCatching {
            JSONObject(FileManager.read(filesDir, "memory/L4_state.json")).optBoolean("onboarding_done", false)
        }.getOrDefault(false)
        if (!onboardingDone) requestNotificationPermission()
        try { LifeScheduler.ensurePeriodic(this) }
        catch (e: Exception) { android.util.Log.e("Ruri", "Could not schedule periodic life", e) }
        startRuriService()
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMessageId = intent.getStringExtra("message_id")
        openPendingMessageIfReady()
    }

    private fun openPendingMessageIfReady() {
        val id = pendingMessageId ?: return
        if (!::web.isInitialized) return
        pendingMessageId = null
        web.evaluateJavascript(
            "window.openMessageById && window.openMessageById(" + JSONObject.quote(id) + ")",
            null
        )
    }

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
        activityResumed = true
        NetState.refresh(this)
        if (::web.isInitialized) {
            runCatching { Bridge().markCommanderSeen() }
            web.evaluateJavascript("window.refreshOnboarding && window.refreshOnboarding()", null)
        }
        if (CommanderReplyQueue.hasPending(filesDir)) attemptPendingTurn()
        pushQueueState()
    }

    override fun onPause() {
        activityResumed = false
        // Do not retry while the screen is off or the app is in the background.
        mainHandler.removeCallbacks(autoRetry)
        retryScheduled = false
        try { BackupPolicy.maybeRolling(this, "pause") }
        catch (e: Exception) { android.util.Log.w("Ruri", "Pause rolling backup failed", e) }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoRetry)
        NetState.unregister()
        super.onDestroy()
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

    // ------------------------------------------------------------ retry plumbing

    /**
     * Runs the queued turn. Transient failures schedule another attempt in ten seconds;
     * terminal failures stop here and wait for the «ПОВТОРИТЬ» button.
     */
    private fun attemptPendingTurn() {
        retryScheduled = false
        if (!activityResumed) return
        if (!CommanderReplyQueue.hasPending(filesDir)) {
            pushQueueState()
            return
        }
        chatExecutor.execute {
            val outcome = runCatching { ChatEngine.respond(filesDir, null) }
            outcome.onSuccess { reply ->
                val messageId = ChatEngine.lastSavedReplyId()
                mainHandler.post {
                    if (!activityResumed && !messageId.isNullOrBlank()) {
                        Notifier.thought(this, reply, messageId)
                    }
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onNativeChatResult && window.onNativeChatResult(" +
                            JSONObject.quote("auto") + "," + JSONObject.quote(reply) + ",null)", null
                    )
                }
            }
            outcome.onFailure { error ->
                val failure = error as? ChatEngine.ModelRequestFailure
                val kind = failure?.failureKind ?: "unexpected"
                scheduleRetryIfNeeded(kind)
                mainHandler.post {
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onNativeChatError && window.onNativeChatError(" +
                            JSONObject.quote("auto") + "," + errorPayload(kind, error.message.orEmpty()) + ")",
                        null
                    )
                }
            }
            mainHandler.post { pushQueueState() }
        }
    }

    private fun scheduleRetryIfNeeded(kind: String) {
        if (!RetryPolicy.isRetryable(kind)) return
        val delay = CommanderReplyQueue.nextDelayMs(filesDir) ?: return
        if (!activityResumed) return
        synchronized(this) {
            if (retryScheduled) return
            retryScheduled = true
        }
        mainHandler.postDelayed(autoRetry, delay.coerceAtLeast(1_000L))
    }

    private fun errorPayload(kind: String, message: String): String {
        val status = CommanderReplyQueue.status(filesDir)
        return JSONObject()
            .put("kind", kind)
            .put("retryable", RetryPolicy.isRetryable(kind))
            .put("label", RetryPolicy.label(kind))
            .put("message", message)
            .put("explanation", RetryPolicy.explain(kind))
            .put("next_retry_at", status.optLong("next_attempt_at", 0L))
            .put("attempt", status.optInt("attempts", 0))
            .toString()
    }

    private fun pushQueueState() {
        if (!::web.isInitialized) return
        val status = CommanderReplyQueue.status(filesDir)
        web.evaluateJavascript(
            "window.onNativeQueueState && window.onNativeQueueState(" + JSONObject.quote(status.toString()) + ")",
            null
        )
    }

    inner class Bridge {
        @JavascriptInterface
        fun getDiagnostics(): String = Diagnostics.run(this@MainActivity)

        @JavascriptInterface
        fun runDiagnostics(): String {
            return try { Diagnostics.runLive(this@MainActivity) }
            catch (e: Exception) { "{\"ok\":false,\"verdict\":" + JSONObject.quote(e.message ?: "diagnostics failed") + "}" }
        }

        @JavascriptInterface
        fun readDiagnosticsLog(): String = Diagnostics.readLog(this@MainActivity)

        @JavascriptInterface
        fun queueStatus(): String = CommanderReplyQueue.status(filesDir).toString()

        @JavascriptInterface
        fun retryPending(): String {
            return try {
                CommanderReplyQueue.manualRetry(filesDir)
                mainHandler.post { attemptPendingTurn() }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "retry failed") }
        }

        @JavascriptInterface
        fun completeChat(callbackId: String, commanderMessageId: String): String {
            if (!callbackId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return "ERROR: invalid callback"
            if (!commanderMessageId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) return "ERROR: invalid message id"
            if (!ChatEngine.isCommanderMessage(filesDir, commanderMessageId)) {
                return "ERROR: Commander message not found in L0"
            }
            chatExecutor.execute {
                runCatching { CommanderReplyQueue.enqueue(filesDir, commanderMessageId) }
                val outcome = runCatching { ChatEngine.respond(filesDir, commanderMessageId) }
                outcome.onSuccess { reply ->
                    val messageId = ChatEngine.lastSavedReplyId()
                    mainHandler.post {
                        if (!activityResumed && !messageId.isNullOrBlank()) {
                            Notifier.thought(this@MainActivity, reply, messageId)
                        }
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatResult && window.onNativeChatResult(" +
                                JSONObject.quote(callbackId) + "," + JSONObject.quote(reply) + ",null)", null
                        )
                    }
                }
                outcome.onFailure { error ->
                    val failure = error as? ChatEngine.ModelRequestFailure
                    val kind = failure?.failureKind ?: "unexpected"
                    scheduleRetryIfNeeded(kind)
                    mainHandler.post {
                        if (::web.isInitialized) web.evaluateJavascript(
                            "window.onNativeChatError && window.onNativeChatError(" +
                                JSONObject.quote(callbackId) + "," + errorPayload(kind, error.message.orEmpty()) + ")",
                            null
                        )
                    }
                }
                mainHandler.post { pushQueueState() }
            }
            return "OK"
        }

        @JavascriptInterface
        fun hasPostNotificationsPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun requestPostNotificationsPermission(): String {
            return try { requestNotificationPermission(); "OK" }
            catch (e: Exception) { "ERROR: " + (e.message ?: "permission request failed") }
        }

        @JavascriptInterface
        fun read(path: String): String = when (path.replace('\\', '/').trimStart('/')) {
            "core/config.json" -> readPublicRuntimeConfig()
            "core/secrets.enc.json" -> ""
            else -> FileManager.read(filesDir, path)
        }

        @JavascriptInterface
        fun readLastEvents(filter: String, limit: Int): String =
            FileManager.readLastEvents(filesDir, filter, limit).toString()

        @JavascriptInterface
        fun readLastLines(path: String, limit: Int): String =
            JSONArray(FileManager.readLastLines(filesDir, path, maxLines = limit.coerceIn(1, 1000))).toString()

        @JavascriptInterface
        fun readMemoryLayer(layer: String): String = when (layer) {
            "L0" -> JSONArray(FileManager.readLastLines(filesDir, "memory/L0_raw.jsonl", maxLines = 100).map {
                runCatching { JSONObject(it) }.getOrNull() ?: JSONObject()
            }).toString()
            "L1" -> FileManager.read(filesDir, "memory/L1_facts.json")
            "L2" -> FileManager.read(filesDir, "memory/L2_diary.json")
            "L3" -> FileManager.read(filesDir, "memory/L3_summary.json")
            "L4" -> FileManager.read(filesDir, "memory/L4_state.json")
            "EMO" -> FileManager.read(filesDir, "memory/emotions.json")
            "SELF" -> FileManager.read(filesDir, "memory/self_model.json")
            else -> "{}"
        }

        @JavascriptInterface
        fun readReceipts(): String = Receipts.export(filesDir)

        @JavascriptInterface
        fun markReadByCommander(id: String): String {
            Receipts.readByCommander(filesDir, id)
            return "OK"
        }

        @JavascriptInterface
        fun deleteMessage(id: String): String {
            return try {
                require(id.isNotBlank() && id.length <= 200) { "Invalid message id" }
                Tombstone.add(filesDir, id)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "delete failed") }
        }

        @JavascriptInterface
        fun isMessageDeleted(id: String): Boolean = Tombstone.isDeleted(filesDir, id)

        @JavascriptInterface
        fun quotaSummary(): String {
            return runCatching {
                if (!Route.hasKey(filesDir)) return@runCatching "Ключ Routeway не задан"
                val state = CommanderReplyQueue.status(filesDir)
                when {
                    state.optInt("pending", 0) == 0 -> Route.DISPLAY_NAME
                    state.optBoolean("manual_retry", false) ->
                        Route.DISPLAY_NAME + " · " + RetryPolicy.label(state.optString("last_error_kind"))
                    else -> Route.DISPLAY_NAME + " · ждёт повтора"
                }
            }.getOrDefault(Route.DISPLAY_NAME)
        }

        @JavascriptInterface
        fun readAutomaticBackupStatus(): String = LocalBackupManager.status(filesDir).toString()

        @JavascriptInterface
        fun createAutomaticBackupNow(): String {
            return try {
                JSONObject().put("created", true)
                    .put("file", LocalBackupManager.createSnapshot(filesDir).name).toString()
            } catch (e: Exception) { "ERROR: " + (e.message ?: "backup failed") }
        }

        @JavascriptInterface
        fun verifyAutomaticBackup(fileName: String): String {
            val safeName = fileName.takeIf { it.matches(Regex("ruri_auto_[0-9]{8}_[0-9]{6}_[0-9]+\\.zip")) }
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
                require(path == "core/character.json") {
                    "Only character settings may be written through the generic WebView bridge"
                }
                FileManager.write(filesDir, path, content)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "write failed") }
        }

        @JavascriptInterface
        fun markOnboardingDone(): String {
            return try {
                FileManager.updateL4State(filesDir) { it.put("onboarding_done", true) }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "onboarding update failed") }
        }

        @JavascriptInterface
        fun readPublicRuntimeConfig(): String {
            return try { SecretStore.readPublicRuntimeConfig(filesDir) }
            catch (e: Exception) { android.util.Log.e("Ruri", "Cannot read public runtime config", e); "{}" }
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
                require(event.optString("type") == "message" && event.optString("who") == "commander") {
                    "The WebView may append Commander messages only"
                }
                require(event.optString("text").isNotBlank()) { "Message text is empty" }
                require(event.optString("id").matches(Regex("[A-Za-z0-9_-]{1,200}"))) {
                    "Invalid Commander message id"
                }
                val saved = JSONObject(FileManager.appendEvent(filesDir, event))
                val messageId = saved.optString("id")
                runCatching { CommanderReplyQueue.enqueue(filesDir, messageId) }
                runCatching { Receipts.delivered(filesDir, messageId) }
                runCatching { OfflineQueue.markPending(filesDir, messageId) }
                mainHandler.post { pushQueueState() }
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
                val latestReply = FileManager.readLastLines(
                    filesDir, "memory/L0_raw.jsonl", maxLines = 500
                ).asReversed().asSequence()
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .firstOrNull { it.optString("type") == "message" && it.optString("who") == "ruri" }
                val id = latestReply?.optString("id").orEmpty()
                if (id.isNotBlank()) Receipts.readByCommander(filesDir, id)
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "receipt update failed") }
        }

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
                val allowedRoots = listOf("core/", "memory/")
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
                    catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Не удалось открыть экспорт", Toast.LENGTH_LONG).show()
                    }
                }
                "OK"
            } catch (e: Exception) { "ERROR: " + (e.message ?: "export failed") }
        }
    }
}
